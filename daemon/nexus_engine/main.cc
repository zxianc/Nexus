// nexus_engine — resident SenseVoice STT + VITS TTS over UDS (line JSON).
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "sherpa-onnx/csrc/offline-recognizer.h"
#include "sherpa-onnx/csrc/offline-tts.h"
#include "sherpa-onnx/csrc/wave-reader.h"
#include "sherpa-onnx/csrc/wave-writer.h"

namespace {

std::string JsonEscape(const std::string &s) {
  std::string o;
  o.reserve(s.size() + 8);
  for (unsigned char c : s) {
    switch (c) {
      case '\\':
        o += "\\\\";
        break;
      case '"':
        o += "\\\"";
        break;
      case '\n':
        o += "\\n";
        break;
      case '\r':
        o += "\\r";
        break;
      case '\t':
        o += "\\t";
        break;
      default:
        o.push_back(static_cast<char>(c));
        break;
    }
  }
  return o;
}

bool JsonGetString(const std::string &line, const char *key, std::string *out) {
  std::string pat = std::string("\"") + key + "\"";
  auto p = line.find(pat);
  if (p == std::string::npos) {
    return false;
  }
  p = line.find(':', p + pat.size());
  if (p == std::string::npos) {
    return false;
  }
  p = line.find('"', p + 1);
  if (p == std::string::npos) {
    return false;
  }
  ++p;
  std::string v;
  while (p < line.size()) {
    char c = line[p++];
    if (c == '\\' && p < line.size()) {
      char n = line[p++];
      if (n == 'n') {
        v.push_back('\n');
      } else if (n == 'r') {
        v.push_back('\r');
      } else if (n == 't') {
        v.push_back('\t');
      } else {
        v.push_back(n);
      }
      continue;
    }
    if (c == '"') {
      break;
    }
    v.push_back(c);
  }
  *out = v;
  return true;
}

bool JsonGetInt(const std::string &line, const char *key, int64_t *out) {
  std::string pat = std::string("\"") + key + "\"";
  auto p = line.find(pat);
  if (p == std::string::npos) {
    return false;
  }
  p = line.find(':', p + pat.size());
  if (p == std::string::npos) {
    return false;
  }
  ++p;
  while (p < line.size() && (line[p] == ' ' || line[p] == '\t')) {
    ++p;
  }
  char *end = nullptr;
  long long v = strtoll(line.c_str() + p, &end, 10);
  if (end == line.c_str() + p) {
    return false;
  }
  *out = static_cast<int64_t>(v);
  return true;
}

bool FileExists(const std::string &p) {
  struct stat st {};
  return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

struct Args {
  std::string sock = "/data/local/tmp/nexus_stt/engine.sock";
  std::string stt_dir = "/data/local/tmp/nexus_stt/sense-voice";
  std::string tts_dir = "/data/local/tmp/nexus_stt/vits-zh-ll";
	std::string lang = "auto";
  int threads = 2;
};

Args ParseArgs(int argc, char **argv) {
  Args a;
  for (int i = 1; i < argc; ++i) {
    std::string s = argv[i];
    auto eq = s.find('=');
    std::string k = eq == std::string::npos ? s : s.substr(0, eq);
    std::string v = eq == std::string::npos
                        ? (i + 1 < argc ? argv[++i] : "")
                        : s.substr(eq + 1);
    if (k == "--sock") {
      a.sock = v;
    } else if (k == "--stt-model-dir") {
      a.stt_dir = v;
    } else if (k == "--tts-model-dir") {
      a.tts_dir = v;
    } else if (k == "--lang") {
      a.lang = v;
    } else if (k == "--threads") {
      a.threads = std::stoi(v);
    } else if (k == "--help" || k == "-h") {
      fprintf(stderr,
              "nexus_engine --sock=PATH --stt-model-dir=DIR --tts-model-dir=DIR "
              "[--lang=auto] [--threads=2]\n");
      exit(0);
    }
  }
  return a;
}

std::unique_ptr<sherpa_onnx::OfflineRecognizer> LoadSTT(const Args &a) {
  sherpa_onnx::OfflineRecognizerConfig cfg;
  cfg.model_config.tokens = a.stt_dir + "/tokens.txt";
  cfg.model_config.sense_voice.model = a.stt_dir + "/model.int8.onnx";
  cfg.model_config.sense_voice.language = a.lang;
  cfg.model_config.sense_voice.use_itn = true;
  cfg.model_config.num_threads = a.threads;
  cfg.model_config.provider = "cpu";
  cfg.model_config.model_type = "sense_voice";
  if (!cfg.Validate()) {
    fprintf(stderr, "STT config invalid\n");
    return nullptr;
  }
  auto t0 = std::chrono::steady_clock::now();
  auto r = std::make_unique<sherpa_onnx::OfflineRecognizer>(cfg);
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0)
                .count();
  fprintf(stderr, "STT loaded in %lld ms\n", (long long)ms);
  return r;
}

std::unique_ptr<sherpa_onnx::OfflineTts> LoadTTS(const Args &a) {
  sherpa_onnx::OfflineTtsConfig cfg;
  cfg.model.vits.model = a.tts_dir + "/model.onnx";
  cfg.model.vits.tokens = a.tts_dir + "/tokens.txt";
  cfg.model.vits.lexicon = a.tts_dir + "/lexicon.txt";
  cfg.model.num_threads = a.threads;
  cfg.model.provider = "cpu";
  std::vector<std::string> fsts;
  for (const char *name : {"phone.fst", "date.fst", "number.fst"}) {
    std::string p = a.tts_dir + "/" + name;
    if (FileExists(p)) {
      fsts.push_back(p);
    }
  }
  if (!fsts.empty()) {
    cfg.rule_fsts = fsts[0];
    for (size_t i = 1; i < fsts.size(); ++i) {
      cfg.rule_fsts += "," + fsts[i];
    }
  }
  if (!cfg.Validate()) {
    fprintf(stderr, "TTS config invalid\n");
    return nullptr;
  }
  auto t0 = std::chrono::steady_clock::now();
  auto t = std::make_unique<sherpa_onnx::OfflineTts>(cfg);
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0)
                .count();
  fprintf(stderr, "TTS loaded in %lld ms\n", (long long)ms);
  return t;
}

std::string HandleLine(const std::string &line,
                       sherpa_onnx::OfflineRecognizer *stt,
                       sherpa_onnx::OfflineTts *tts, std::mutex *mu) {
  int64_t id = 0;
  JsonGetInt(line, "id", &id);
  std::string op;
  if (!JsonGetString(line, "op", &op)) {
    return "{\"id\":" + std::to_string(id) +
           ",\"ok\":false,\"err\":\"missing op\"}\n";
  }
  if (op == "ping") {
    return "{\"id\":" + std::to_string(id) + ",\"ok\":true}\n";
  }

  std::lock_guard<std::mutex> lock(*mu);
  auto t0 = std::chrono::steady_clock::now();

  if (op == "stt") {
    std::string wav;
    if (!JsonGetString(line, "wav", &wav) || wav.empty()) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"missing wav\"}\n";
    }
    int32_t rate = 0;
    bool ok = false;
    auto samples = sherpa_onnx::ReadWave(wav, &rate, &ok);
    if (!ok) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"read wav failed\"}\n";
    }
    auto stream = stt->CreateStream();
    stream->AcceptWaveform(rate, samples.data(), samples.size());
    stt->DecodeStream(stream.get());
    std::string text = stream->GetResult().text;
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0)
                  .count();
    fprintf(stderr, "stt id=%lld ms=%lld text=%s\n", (long long)id,
            (long long)ms, text.c_str());
    return "{\"id\":" + std::to_string(id) + ",\"ok\":true,\"text\":\"" +
           JsonEscape(text) + "\",\"ms\":" + std::to_string(ms) + "}\n";
  }

  if (op == "tts") {
    std::string text, wav;
    int64_t sid = 0;
    JsonGetInt(line, "sid", &sid);
    if (!JsonGetString(line, "text", &text) || text.empty()) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"missing text\"}\n";
    }
    if (!JsonGetString(line, "wav", &wav) || wav.empty()) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"missing wav\"}\n";
    }
    sherpa_onnx::GenerationConfig gen;
    gen.sid = static_cast<int32_t>(sid);
    gen.speed = 1.0f;
    auto audio = tts->Generate(text, gen, nullptr);
    if (audio.samples.empty()) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"tts empty\"}\n";
    }
    if (!sherpa_onnx::WriteWave(wav, audio.sample_rate, audio.samples.data(),
                                audio.samples.size())) {
      return "{\"id\":" + std::to_string(id) +
             ",\"ok\":false,\"err\":\"write wav failed\"}\n";
    }
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0)
                  .count();
    fprintf(stderr, "tts id=%lld ms=%lld rate=%d samples=%zu\n", (long long)id,
            (long long)ms, audio.sample_rate, audio.samples.size());
    return "{\"id\":" + std::to_string(id) +
           ",\"ok\":true,\"rate\":" + std::to_string(audio.sample_rate) +
           ",\"wav\":\"" + JsonEscape(wav) +
           "\",\"ms\":" + std::to_string(ms) + "}\n";
  }

  return "{\"id\":" + std::to_string(id) +
         ",\"ok\":false,\"err\":\"unknown op\"}\n";
}

void ServeClient(int cfd, sherpa_onnx::OfflineRecognizer *stt,
                 sherpa_onnx::OfflineTts *tts, std::mutex *mu) {
  std::string buf;
  char tmp[4096];
  while (true) {
    ssize_t n = read(cfd, tmp, sizeof(tmp));
    if (n <= 0) {
      break;
    }
    buf.append(tmp, tmp + n);
    size_t pos;
    while ((pos = buf.find('\n')) != std::string::npos) {
      std::string line = buf.substr(0, pos);
      buf.erase(0, pos + 1);
      if (line.empty()) {
        continue;
      }
      std::string resp = HandleLine(line, stt, tts, mu);
      const char *p = resp.data();
      size_t left = resp.size();
      while (left > 0) {
        ssize_t w = write(cfd, p, left);
        if (w <= 0) {
          close(cfd);
          return;
        }
        p += w;
        left -= static_cast<size_t>(w);
      }
    }
  }
  close(cfd);
}

}  // namespace

int main(int argc, char **argv) {
  Args args = ParseArgs(argc, argv);
  fprintf(stderr, "nexus_engine starting sock=%s stt=%s tts=%s threads=%d\n",
          args.sock.c_str(), args.stt_dir.c_str(), args.tts_dir.c_str(),
          args.threads);

  auto stt = LoadSTT(args);
  auto tts = LoadTTS(args);
  if (!stt || !tts) {
    return 1;
  }

  unlink(args.sock.c_str());
  int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (lfd < 0) {
    perror("socket");
    return 1;
  }
  sockaddr_un addr {};
  addr.sun_family = AF_UNIX;
  if (args.sock.size() >= sizeof(addr.sun_path)) {
    fprintf(stderr, "sock path too long\n");
    return 1;
  }
  std::snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", args.sock.c_str());
  if (bind(lfd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
    perror("bind");
    return 1;
  }
  chmod(args.sock.c_str(), 0666);
  if (listen(lfd, 4) != 0) {
    perror("listen");
    return 1;
  }
  fprintf(stderr, "nexus_engine ready on %s\n", args.sock.c_str());

  std::mutex mu;
  while (true) {
    int cfd = accept(lfd, nullptr, nullptr);
    if (cfd < 0) {
      if (errno == EINTR) {
        continue;
      }
      perror("accept");
      break;
    }
    ServeClient(cfd, stt.get(), tts.get(), &mu);
  }
  close(lfd);
  unlink(args.sock.c_str());
  return 0;
}
