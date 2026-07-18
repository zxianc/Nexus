/* Magisk Zygisk API - v4 (Simplified for Module Development) */
#ifndef ZYGISK_H
#define ZYGISK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    void *impl;
} ZygiskApi;

class ZygiskModuleBase {
public:
    virtual ~ZygiskModuleBase() {}
    virtual void onLoad(ZygiskApi *api, void *env) {}
    virtual void preAppFork(void *env) {}
    virtual void postAppFork(void *env) {}
    virtual void preServerFork(void *env) {}
    virtual void postServerFork(void *env) {}
};

#define REGISTER_ZYGISK_MODULE(clazz) \
    extern "C" ZygiskModuleBase *zygisk_module_entry() { return new clazz(); }

#ifdef __cplusplus
}
#endif

#endif // ZYGISK_H