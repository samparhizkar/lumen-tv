/* Minimal config.h for building libplist against Android bionic (API 28+).
   All referenced features exist in bionic. */
#ifndef PLIST_CONFIG_H
#define PLIST_CONFIG_H

#define PACKAGE_VERSION "2.6.0-android"
#define HAVE_GMTIME_R 1
#define HAVE_LOCALTIME_R 1
#define HAVE_MEMMEM 1
#define HAVE_STRNDUP 1
#define HAVE_STRPTIME 1
#define HAVE_TM_TM_GMTOFF 1
#define HAVE_TM_TM_ZONE 1

#endif
