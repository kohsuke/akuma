package com.sun.akuma;

import com.sun.jna.StringArray;
import static com.sun.akuma.CLibrary.LIBC;

import java.io.FileWriter;
import java.io.IOException;
import java.io.File;

/**
 * Forks a copy of the current process into the background.
 *
 * <p>
 * Because of the fork/exec involved in doing this, your code has to call Daemonizer in a certain sequence.
 * Specifically, from your main method:
 * <pre>
 * public static void main(String[] args) {
 *     Daemonizer d = new Daemonizer();
 *     if(d.isDaemonized()) {
 *         // perform initialization as a daemon
 *         // this involves in closing file descriptors, recording PIDs, etc.
 *         d.{@linkplain #init() init}();
 *     } else {
 *         // if you are already daemonized, no point in daemonizing yourself again,
 *         // so do this only when you aren't daemonizing.
 *         if(you decide to launch a copy into background) {
 *             d.daemonize(...);
 *             System.exit(0);
 *         }
 *     }
 *
 *     // your normal main code follows
 *     // this part can be executed in two ways
 *     // 1) the user runs your process in the foreground
 *     // 2) you decided to daemonize yourself, in which case the newly forked daemon will execute this code,
 *     //    while the originally executed foreground Java process exits before it gets here.
 *     ...
 * }
 * </pre>
 *
 * <p>
 * Alternatively, your main class can extend from Daemon, so that you can customize some of the behaviors.
 *
 * @author Kohsuke Kawaguchi
 */
public class Daemon {
    /**
     * Returns true if the current process is already launched as a daemon
     * via {@link #daemonize()}.
     */
    public boolean isDaemonized() {
        return System.getProperty(Daemon.class.getName())!=null;
    }

    /**
     * Relaunches the JVM with the exact same arguments into the daemon.
     */
    public void daemonize() throws IOException {
        daemonize(JavaVMArguments.current());
    }

    /**
     * Relaunches the JVM with the given arguments into the daemon.
     */
    public void daemonize(JavaVMArguments args) {
        if(isDaemonized())
            throw new IllegalStateException("Already running as a daemon");

        // let the child process now that it's a daemon
        args.setSystemProperty(Daemon.class.getName(),"daemonized");

        // prepare for a fork
        String exe = getCurrentExecutable();
        StringArray sa = args.toStringArray();

        int i = LIBC.fork();
        if(i<0) {
            LIBC.perror("initial fork failed");
            System.exit(-1);
        }
        if(i==0) {
            // with fork, we lose all the other critical threads, to exec to Java again
            LIBC.execv(exe,sa);
            System.err.println("exec failed");
            LIBC.perror("initial exec failed");
            System.exit(-1);
        }

        // parent exits
    }

    /**
     * Overwrites the current process with a new Java VM with the given JVM arguments.
     */
    public static void selfExec(JavaVMArguments args) {
        LIBC.execv(getCurrentExecutable(), args.toStringArray());
    }

    /**
     * Prepares the current process to act as a daemon.
     */
    @SuppressWarnings({"OctalInteger"})
    public void init() throws Exception {
        // start a new process session
        LIBC.setsid();

        closeDescriptors();

        // restrict the creation mode to 750 (octal)
        LIBC.umask(0027);

        chdirToRoot();
        writePidFile();
    }

    /**
     * Closes inherited file descriptors.
     *
     * <p>
     * This method can be overridden to no-op in a subtype. Useful for debugging daemon processes
     * when they don't work correctly.
     */
    protected void closeDescriptors() throws IOException {
        if(Boolean.getBoolean(Daemon.class.getName()+".keepDescriptors")) {
            System.out.close();
            System.err.close();
            System.in.close();
        }

        // ideally we'd like to close all other descriptors, but that would close
        // jar files used as classpath, and break JVM.
    }

    /**
     * change directory to '/' to avoid locking directories.
     */
    protected void chdirToRoot() {
        LIBC.chdir("/");
        System.setProperty("user.dir","/");
    }

    /**
     * Writes out a PID file.
     */
    protected void writePidFile() throws IOException {
        try {
            FileWriter fw = new FileWriter("/var/run/daemon.pid");
            fw.write(String.valueOf(LIBC.getpid()));
            fw.close();
        } catch (IOException e) {
            // if failed to write, keep going because maybe we are run from non-root
        }
    }

    /**
     * Gets the current executable name.
     */
    public static String getCurrentExecutable() {
        int pid = LIBC.getpid();
        String name = "/proc/" + pid + "/exe";
        if(new File(name).exists())
            return name;

        // cross-platform fallback
        return System.getProperty("java.home")+"/bin/java";
    }
}
