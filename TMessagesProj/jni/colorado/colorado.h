#include <stdbool.h>

#ifdef NDEBUG
#define LOG_DISABLED
#endif
#define PACKAGE_NAME "tw.nekomimi.nekogram"_iobfs.c_str()
#define CERT_HASH 0x9efe75eb
#define CERT_SIZE 0x381

#ifdef __cplusplus
extern "C" {
#endif

bool check_signature();

#ifdef __cplusplus
}
#endif