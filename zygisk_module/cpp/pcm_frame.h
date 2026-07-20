#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sys/types.h>

static constexpr uint32_t kApcmMagic = 0x4D435041u;
static constexpr uint8_t kTypePcmDl = 0x01;
static constexpr uint8_t kTypePcmUl = 0x02;
static constexpr uint8_t kTypeCtrlMute = 0x10;
static constexpr uint8_t kTypeCtrlFlushUl = 0x11;
static constexpr uint8_t kTypeCtrlSession = 0x12;
static constexpr size_t kMaxFramePayload = 64 * 1024;

// Returns total bytes written to out, or -1 on error.
inline ssize_t pcm_frame_encode(uint8_t type, uint8_t flags, const void *payload, size_t len,
                                uint8_t *out, size_t out_cap) {
    if (len > kMaxFramePayload || out_cap < 4 + len) {
        return -1;
    }
    out[0] = type;
    out[1] = flags;
    out[2] = (uint8_t)(len & 0xff);
    out[3] = (uint8_t)((len >> 8) & 0xff);
    if (len && payload) {
        memcpy(out + 4, payload, len);
    }
    return (ssize_t)(4 + len);
}
