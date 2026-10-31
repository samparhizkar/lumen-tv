/*
 * Classic RAOP RSA (see raop_rsa.h). The key below is the AirPort Express
 * private key published with shairport-sync (common.c, super_secret_key).
 */
#include <string.h>

#include <openssl/bio.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>

#include "raop_rsa.h"

static const char airport_key_pem[] =
    "-----BEGIN RSA PRIVATE KEY-----\n"
    "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Q\n"
    "g90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6\n"
    "DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLv\n"
    "qr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M\n"
    "+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEp\n"
    "VviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfWBLmk\n"
    "zkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14n\n"
    "DY4TFQAaLlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPsc\n"
    "EsA5ltpxOgUGCY7b7ez5NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZu\n"
    "NGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jmlpPHr0O/KnPQtzI3eguhe0TwUem/eYSd\n"
    "yzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurcizaaA/L0HIgAmOit1GJA2s\n"
    "aMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFua39GLS99\n"
    "ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrn\n"
    "jndMoPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v\n"
    "//mU8eVkQaoANf0ZoMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtk\n"
    "rfa7ef+AUb69DNggq4mHQAYBp7L+k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQ\n"
    "NepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hLAoGBANDrr7xAJbqBjHVwIzQ4\n"
    "To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvAcJyRM9SJ7OKl\n"
    "Gt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf\n"
    "54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TF\n"
    "BVmD7fV0Zhov17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLa\n"
    "lpGSwomSNYJcB9HNMlmhkGzc1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9\n"
    "+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gILAuE4Pu13aKiJnfft7hIjbK+5kyb\n"
    "3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ2gG0N5hvJpzwwhbh\n"
    "XqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=\n"
    "-----END RSA PRIVATE KEY-----\n";

static EVP_PKEY *
airport_key(void)
{
    static EVP_PKEY *key = NULL;   /* loaded once, kept for the process lifetime */
    if (!key) {
        BIO *bio = BIO_new_mem_buf(airport_key_pem, -1);
        if (bio) {
            key = PEM_read_bio_PrivateKey(bio, NULL, NULL, NULL);
            BIO_free(bio);
        }
    }
    return key;
}

/* base64 that tolerates missing '=' padding; returns decoded length or -1 */
static int
b64_decode(const char *in, unsigned char *out, size_t outmax)
{
    char buf[512];
    size_t len = strlen(in);
    if (len + 4 > sizeof(buf)) return -1;
    memcpy(buf, in, len);
    while (len % 4) buf[len++] = '=';
    buf[len] = '\0';
    unsigned char tmp[384];
    if (len / 4 * 3 > sizeof(tmp)) return -1;
    int n = EVP_DecodeBlock(tmp, (const unsigned char *) buf, (int) len);
    if (n < 0) return -1;
    for (size_t i = len; i > 0 && buf[i - 1] == '='; i--) n--;
    if (n > (int) outmax) return -1;
    memcpy(out, tmp, n);
    return n;
}

int
raop_rsa_apple_response(const char *challenge_b64, const unsigned char *local_ip, int local_iplen,
                        const unsigned char hw_addr[6], char *out, size_t outlen)
{
    EVP_PKEY *key = airport_key();
    if (!key) return -1;

    /* challenge (16) | ip (4 or 16) | hw addr (6), zero-padded to at least 32 bytes */
    unsigned char msg[48] = { 0 };
    int n = b64_decode(challenge_b64, msg, 16);
    if (n != 16) return -1;
    if (local_iplen == 16 && !memcmp(local_ip, "\0\0\0\0\0\0\0\0\0\0\xff\xff", 12)) {
        local_ip += 12;   /* IPv4-mapped IPv6: senders sign the IPv4 form */
        local_iplen = 4;
    }
    if (local_iplen != 4 && local_iplen != 16) return -1;
    memcpy(msg + n, local_ip, local_iplen);
    n += local_iplen;
    memcpy(msg + n, hw_addr, 6);
    n += 6;
    if (n < 32) n = 32;

    /* raw PKCS#1 v1.5 type-1 "private encrypt" (sign without a digest) */
    unsigned char sig[256];
    size_t siglen = sizeof(sig);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(key, NULL);
    int ok = ctx && EVP_PKEY_sign_init(ctx) > 0 &&
             EVP_PKEY_CTX_set_rsa_padding(ctx, RSA_PKCS1_PADDING) > 0 &&
             EVP_PKEY_sign(ctx, sig, &siglen, msg, n) > 0;
    EVP_PKEY_CTX_free(ctx);
    if (!ok) return -1;

    if (outlen < 4 * ((siglen + 2) / 3) + 1) return -1;
    int len = EVP_EncodeBlock((unsigned char *) out, sig, (int) siglen);
    while (len > 0 && out[len - 1] == '=') out[--len] = '\0';   /* senders expect it unpadded */
    return 0;
}

int
raop_rsa_decrypt_aeskey(const unsigned char *in, size_t inlen, unsigned char aeskey[16])
{
    EVP_PKEY *key = airport_key();
    if (!key) return -1;
    unsigned char plain[256];
    size_t plainlen = sizeof(plain);
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new(key, NULL);
    int ok = ctx && EVP_PKEY_decrypt_init(ctx) > 0 &&
             EVP_PKEY_CTX_set_rsa_padding(ctx, RSA_PKCS1_OAEP_PADDING) > 0 &&
             EVP_PKEY_decrypt(ctx, plain, &plainlen, in, inlen) > 0;
    EVP_PKEY_CTX_free(ctx);
    if (!ok || plainlen != 16) return -1;
    memcpy(aeskey, plain, 16);
    return 0;
}
