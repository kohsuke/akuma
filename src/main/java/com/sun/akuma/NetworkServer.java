package com.sun.akuma;

import com.sun.jna.StringArray;

import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketImpl;
import java.util.logging.Logger;
import java.util.List;
import java.util.Collections;
import java.util.Arrays;

import static com.sun.akuma.CLibrary.LIBC;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Multi-process network server that accepts connections on the same TCP port.
 *
 * <p>
 * This class lets you write a Unix-like multi-process network daemon. The first process acts
 * as the frontend. This creates a new socket, then fork several worker processes, which inherits
 * this socket.
 *
 * <p>
 * Worker threads will all accept connections on this port, so even when one of the worker processes
 * die off, your clients won't notice that there's a problem.
 *
 * <p>
 * The user of this class needs to override this class and implement abstract methods.
 * Several protected methods can be also overridden to customize the behaviors.
 * See {@link EchoServer} source code as an example.
 *
 * <p>
 * This class also inherits from {@link Daemon} to support the daemonization.
 *
 * <p>
 * From your main method, call into {@link #run()} method. Depending on whether the current process
 * is started as a front end or a worker process, the run method behave accordingly.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class NetworkServer extends Daemon {
    /**
     * Java arguments.
     */
    protected final List<String> arguments;

    protected NetworkServer(String[] args) {
        this.arguments = Collections.unmodifiableList(Arrays.asList(args));
    }

    /**
     * Entry point. Should be called from your main method.
     */
    public void run() throws Exception {
        String mode = System.getProperty(MODE_PROPERTY);
        if("worker".equals(mode)) {
            // worker process
            worker();
        } else {
            // to run the frontend in the foreground
            if(isDaemonized()) {
                // running as a daemon
                init();
            } else {
                // running in the foreground
                if(shouldBeDaemonized()) {
                    // to launch the whole thing into a daemon
                    daemonize();
                    System.exit(0);
                }
            }

            frontend();
        }
    }

    /**
     * Determine if we should daemonize ourselves.
     */
    protected boolean shouldBeDaemonized() {
        return !arguments.isEmpty() && arguments.get(0).equals("daemonize");
    }

    /**
     * Front-end.
     */
    protected void frontend() throws Exception {
        ServerSocket ss = createServerSocket();
        int fdn = getUnixFileDescriptor(ss);

        LOGGER.fine("Listening to port "+ss.getLocalPort()+" (fd="+fdn+")");

        // prepare the parameters for the exec.
        JavaVMArguments forkArgs = JavaVMArguments.current();
        forkArgs.setSystemProperty(NetworkServer.class.getName()+".port",String.valueOf(fdn));

        forkWorkers(forkArgs);
    }

    /**
     * Forks the worker thread with the given JVM args.
     *
     * The implementation is expected to modify the arguments to suit their need,
     * then call into {@link #forkWorkerThreads(JavaVMArguments, int)}.
     */
    protected abstract void forkWorkers(JavaVMArguments args) throws Exception;

    /**
     * Called by the front-end code to fork a number of worker processes into the background.
     *
     * This method never returns.
     */
    protected void forkWorkerThreads(JavaVMArguments arguments, int n) throws Exception {
        String exe = Daemon.getCurrentExecutable();
        arguments.setSystemProperty(MODE_PROPERTY,"worker"); // the forked process should run as workers
        LOGGER.fine("Forking worker: "+arguments);
        StringArray sa = arguments.toStringArray();

        // fork several worker processes
        for( int i=0; i< n; i++ ) {
            int r = LIBC.fork();
            if(r<0) {
                LIBC.perror("forking a worker process failed");
                System.exit(-1);
            }
            if(r==0) {
                // newly created child will exec to itself to get the proper Java environment back
                LIBC.execv(exe,sa);
                System.err.println("exec failed");
                LIBC.perror("initial exec failed");
                System.exit(-1);
            }
        }

        // when we are killed, kill all the worker processes, too.
        Signal.handle(new Signal("TERM"),
            new SignalHandler() {
                public void handle(Signal sig) {
                    LIBC.kill(0,SIGTERM);
                    System.exit(-1);
                }
            });

        // hang forever
        Object o = new Object();
        synchronized (o) {
            o.wait();
        }
    }

    /**
     * Creates a bound {@link ServerSocket} that will be shared by all worker processes.
     * This method is called in the frontend process.
     */
    protected abstract ServerSocket createServerSocket() throws Exception;

    /**
     * Determines the Unix file descriptor number of the given {@link ServerSocket}.
     */
    private int getUnixFileDescriptor(ServerSocket ss) throws NoSuchFieldException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Field $impl = ss.getClass().getDeclaredField("impl");
        $impl.setAccessible(true);
        SocketImpl socketImpl = (SocketImpl)$impl.get(ss);
        Method $getFileDescriptor = SocketImpl.class.getDeclaredMethod("getFileDescriptor");
        $getFileDescriptor.setAccessible(true);
        FileDescriptor fd = (FileDescriptor) $getFileDescriptor.invoke(socketImpl);
        Field $fd = fd.getClass().getDeclaredField("fd");
        $fd.setAccessible(true);
        return (Integer)$fd.get(fd);
    }

    protected void worker() throws Exception {
        String port = System.getProperty(NetworkServer.class.getName() + ".port");
        worker(recreateServerSocket(Integer.parseInt(port)));
    }

    /**
     * Worker thread main code.
     *
     * @param ss
     *      The server socket that the frontend process created.
     */
    protected abstract void worker(ServerSocket ss) throws Exception;

    /**
     * Recreates a bound {@link ServerSocket} on the given file descriptor.
     */
    private ServerSocket recreateServerSocket(int fdn) throws Exception {
        // create a properly populated FileDescriptor
        FileDescriptor fd = new FileDescriptor();
        Field $fd = FileDescriptor.class.getDeclaredField("fd");
        $fd.setAccessible(true);
        $fd.set(fd,fdn);

        // now create a PlainSocketImpl
        Class $PlainSocketImpl = Class.forName("java.net.PlainSocketImpl");
        Constructor $init = $PlainSocketImpl.getDeclaredConstructor(FileDescriptor.class);
        $init.setAccessible(true);
        SocketImpl socketImpl = (SocketImpl)$init.newInstance(fd);

        // then wrap that into ServerSocket
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(0));
        Field $impl = ServerSocket.class.getDeclaredField("impl");
        $impl.setAccessible(true);
        $impl.set(ss,socketImpl);
        return ss;
    }

    private static final Logger LOGGER = Logger.getLogger(NetworkServer.class.getName());
    private static final int SIGTERM = 15;
    private static final String MODE_PROPERTY = NetworkServer.class.getName() + ".mode";
}
