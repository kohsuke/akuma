package com.sun.akuma;

import com.sun.jna.Library;
import com.sun.jna.StringArray;
import com.sun.jna.Native;

/**
 * GNU C library.
 */
public interface CLibrary extends Library {
    int fork();
    int kill(int pid, int signum);
    int setsid();
    int umask(int mask);
    int getpid();
    int getppid();
    int chdir(String dir);
    int execv(String file, StringArray args);
    int setenv(String name, String value);
    int unsetenv(String name);
    void perror(String msg);

    public static final CLibrary LIBC = (CLibrary) Native.loadLibrary("c",CLibrary.class);
}
