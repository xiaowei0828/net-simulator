#ifndef LAB_ARCH_CC_H
#define LAB_ARCH_CC_H
#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/time.h>
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_RAND() arc4random()
#endif
