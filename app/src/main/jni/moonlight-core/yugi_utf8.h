#pragma once

#include <stddef.h>
#include <stdint.h>

// UTF-16 (Java chars) to standard UTF-8. Surrogate pairs become one 4-byte sequence; a lone
// surrogate becomes U+FFFD. `out` needs room for 3 bytes per input unit. Returns the byte count.
static size_t yugiUtf16ToUtf8(const uint16_t* in, size_t len, char* out) {
    size_t o = 0;
    for (size_t i = 0; i < len; i++) {
        uint32_t c = in[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && in[i + 1] >= 0xDC00 && in[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (in[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out[o++] = (char) c;
        } else if (c < 0x800) {
            out[o++] = (char) (0xC0 | (c >> 6));
            out[o++] = (char) (0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out[o++] = (char) (0xE0 | (c >> 12));
            out[o++] = (char) (0x80 | ((c >> 6) & 0x3F));
            out[o++] = (char) (0x80 | (c & 0x3F));
        } else {
            out[o++] = (char) (0xF0 | (c >> 18));
            out[o++] = (char) (0x80 | ((c >> 12) & 0x3F));
            out[o++] = (char) (0x80 | ((c >> 6) & 0x3F));
            out[o++] = (char) (0x80 | (c & 0x3F));
        }
    }
    out[o] = 0;
    return o;
}
