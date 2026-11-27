#ifndef FAN_SHA256_H
#define FAN_SHA256_H
#include <stddef.h>
#include <stdint.h>
struct fan_sha256 { uint32_t h[8]; uint64_t bytes; unsigned used; unsigned char block[64]; };
void fan_sha256_init(struct fan_sha256 *s);
void fan_sha256_update(struct fan_sha256 *s, const void *data, size_t n);
void fan_sha256_final(struct fan_sha256 *s, unsigned char out[32]);
void fan_sha256_hex(const void *data, size_t n, char out[65]);
#endif
