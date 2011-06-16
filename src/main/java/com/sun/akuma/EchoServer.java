/*
 * The MIT License
 *
 * Copyright (c) 2009-, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.sun.akuma;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;

import static com.sun.akuma.CLibrary.LIBC;

/**
 * Sample echo server.
 *
 * @author Kohsuke Kawaguchi
 */
public class EchoServer extends NetworkServer {
    public static void main(String[] args) throws Exception {
        new EchoServer(args).run();
    }

    public EchoServer(String[] args) {
        super(args);
    }

    /**
     * Daemonize if something is given as arguments.
     */
    @Override
    protected boolean shouldBeDaemonized() {
        return !arguments.isEmpty();
    }

    @Override
    protected void frontend() throws Exception {
        System.out.println("This is a simple echo server. Run with some argument to fork into a daemon, then try 'nc localhost 12345' from several terminals.");
        super.frontend();
    }

    @Override
    protected void forkWorkers(JavaVMArguments args) throws Exception {
        // TODO: parse arguments and decide how many to fork
        forkWorkerThreads(args, 2);
    }

    @Override
    protected ServerSocket createServerSocket() throws Exception {
        System.out.println("Listening on port 12345");
        // TODO: parse arguments and decide port
        return new ServerSocket(12345);
    }

    @Override
    protected void worker(ServerSocket ss) throws Exception {
        byte[] buf = new byte[1024];
        // run a simple echo server
        while(true) {
            Socket s = ss.accept();
            System.out.println("PID:"+ LIBC.getpid()+" accepted a new connection");

            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            int len;
            while((len=in.read(buf))>=0)
                out.write(buf,0,len);

            s.close();
        }
    }
}
