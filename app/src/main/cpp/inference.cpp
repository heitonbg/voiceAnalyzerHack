#include "llama.h"
#include <jni.h>
#include <sys/resource.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_epicwalk_voiceanalyzer_llm_NativeLlm_peakRssKiB(JNIEnv *, jobject) {
    struct rusage usage {};
    if (getrusage(RUSAGE_SELF, &usage)) return -1;
    return usage.ru_maxrss;
}
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::once_flag backend;
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string bytes(JNIEnv *env, jbyteArray value) {
    const auto n = env->GetArrayLength(value);
    std::string result(n, '\0');
    env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_epicwalk_voiceanalyzer_llm_NativeLlm_open(JNIEnv *env, jobject, jstring path) {
    try {
        std::call_once(backend, [] { llama_backend_init(); });
        const char *raw = env->GetStringUTFChars(path, nullptr);
        if (!raw) return 0;
        std::string file(raw);
        env->ReleaseStringUTFChars(path, raw);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        auto *model = llama_model_load_from_file(file.c_str(), params);
        if (!model) throw std::runtime_error("Cannot load bundled Qwen GGUF");
        return reinterpret_cast<jlong>(model);
    } catch (const std::exception &error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_epicwalk_voiceanalyzer_llm_NativeLlm_close(JNIEnv *, jobject, jlong handle) {
    if (handle) llama_model_free(reinterpret_cast<llama_model *>(handle));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_epicwalk_voiceanalyzer_llm_NativeLlm_generate(JNIEnv *env, jobject, jlong handle,
        jbyteArray promptBytes, jbyteArray grammarBytes) {
    try {
        auto *model = reinterpret_cast<llama_model *>(handle);
        if (!model) throw std::runtime_error("LLM session is closed");
        std::string prompt = bytes(env, promptBytes), grammar = bytes(env, grammarBytes);
        auto params = llama_context_default_params();
        params.n_ctx = 2048; params.n_batch = 128; params.n_ubatch = 128;
        params.n_threads = 2; params.n_threads_batch = 2;
        params.offload_kqv = false; params.op_offload = false;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model, params), llama_free);
        if (!context) throw std::runtime_error("LLM context allocation failed");
        auto *vocab = llama_model_get_vocab(model);
        const int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (n < 1 || n > 1650) throw std::runtime_error("LLM prompt exceeds context budget");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) < 0)
            throw std::runtime_error("LLM tokenization failed");
        for (int offset = 0; offset < n; offset += 128) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, n - offset));
            if (llama_decode(context.get(), batch)) throw std::runtime_error("LLM prefill failed");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto *constraint = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!constraint) throw std::runtime_error("Invalid output grammar");
        llama_sampler_chain_add(sampler.get(), constraint);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        bool ended = false;
        for (int i = 0; i < 320; ++i) {
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[1024];
            const int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size < 0) throw std::runtime_error("LLM token piece too large");
            output.append(piece, size);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch)) throw std::runtime_error("LLM decode failed");
        }
        if (!ended) throw std::runtime_error("LLM output token budget exhausted");
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception &error) { fail(env, error.what()); return nullptr; }
}
