#include "llama.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <vector>

using Clock = std::chrono::steady_clock;
static long long elapsed(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}
struct Backend {
    Backend() { llama_backend_init(); }
    ~Backend() { llama_backend_free(); }
};
int main(int argc, char **argv) {
    try {
        if (argc != 4) throw std::runtime_error("Usage: smoke model.gguf prompt.txt output.txt");
        std::ifstream prompt_file(argv[2], std::ios::binary);
        if (!prompt_file) throw std::runtime_error("Cannot read real prompt");
        std::string prompt((std::istreambuf_iterator<char>(prompt_file)), {});
        if (prompt.empty() || prompt.size() > 20000) throw std::runtime_error("Invalid prompt size");
        Backend backend;
        const auto load_start = Clock::now();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(argv[1], model_params), llama_model_free);
        if (!model) throw std::runtime_error("GGUF model load failed");
        const auto load_ms = elapsed(load_start);
        auto params = llama_context_default_params();
        params.n_ctx = 2048;
        params.n_batch = 128;
        params.n_ubatch = 128;
        params.n_threads = 2;
        params.n_threads_batch = 2;
        params.offload_kqv = false;
        params.op_offload = false;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), params), llama_free);
        if (!context) throw std::runtime_error("Context allocation failed");
        const auto *vocab = llama_model_get_vocab(model.get());
        const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 1800) throw std::runtime_error("Prompt exceeds smoke context budget");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0)
            throw std::runtime_error("Tokenization failed");
        const auto prefill_start = Clock::now();
        for (int offset = 0; offset < count; offset += 128) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, count - offset));
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Prefill failed");
        }
        const auto prefill_ms = elapsed(prefill_start);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Sampler allocation failed");
        std::ofstream output(argv[3], std::ios::binary);
        if (!output) throw std::runtime_error("Cannot create inference output");
        const auto generation_start = Clock::now();
        int generated = 0;
        bool reached_eos = false;
        for (; generated < 192; ++generated) {
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { reached_eos = true; break; }
            char buffer[512];
            int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
            if (size < 0) throw std::runtime_error("Token piece exceeds buffer");
            output.write(buffer, size);
            output.flush();
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Generation failed");
        }
        if (!generated) throw std::runtime_error("No generated tokens");
        struct rusage usage {};
        getrusage(RUSAGE_SELF, &usage);
        std::fprintf(stderr,
            "PRIVATE_SCRIBE_METRICS {\"load_ms\":%lld,\"prefill_ms\":%lld,"
            "\"generation_ms\":%lld,\"generated_tokens\":%d,\"reached_eos\":%s,"
            "\"maxrss_kib\":%ld,\"threads\":2,\"context\":2048}\n",
            load_ms, prefill_ms, elapsed(generation_start), generated,
            reached_eos ? "true" : "false", usage.ru_maxrss);
        llama_perf_context_print(context.get());
        return 0;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Smoke inference failed: %s\n", error.what());
        return 1;
    }
}
