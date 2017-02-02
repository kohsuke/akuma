package com.sun.akuma.test;

import com.sun.akuma.Daemon;
import junit.framework.TestCase;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.Random;
import static com.sun.akuma.CLibrary.LIBC;


public class Dup2Test extends TestCase {

    static final Random rand = new Random(System.currentTimeMillis());

    // Attempting to write this test using stdout / stderr works within IntelliJ, but
    // the surefire test runner writes to a cached reference to System.out during the test
    // and corrupts the results. So make the same test using other files.
    public void testDup2() throws Exception {
        /* dup fileA into fileB, so that writes to A really go to B */
        File fileA = File.createTempFile("dup2-testA", ".txt"),
                fileB = File.createTempFile("dup2-testB", ".txt");
        FileOutputStream outA = new FileOutputStream(fileA),
                outB = new FileOutputStream(fileB);
        String textA = String.valueOf(rand.nextLong()), textB = String.valueOf(rand.nextLong()),
                textC = String.valueOf(rand.nextLong());
        try {
            outA.write(textA.getBytes(Charset.defaultCharset()));
            outB.write(textB.getBytes(Charset.defaultCharset()));
            int bfd = getFD(outB.getFD());
            Daemon.dupFD(outA.getFD(), bfd);
            outB.write(textC.getBytes(Charset.defaultCharset()));
        } finally {
            outA.close();
            outB.close();
        }
        String contentA = readFileContents(fileA);
        String contentB = readFileContents(fileB);
        assertEquals("expect only textB in file B", textB, contentB);
        assertEquals("expect textA + textC in file A", textA + textC, contentA);
    }


    private static int getFD(FileDescriptor fd) throws Exception {
        Field fdField = FileDescriptor.class.getDeclaredField("fd");
        fdField.setAccessible(true);
        return fdField.getInt(fd);
    }

    private static String readFileContents(File f) throws IOException {
        /* assuming this project wants to target back to java6, so do not use Files.readAllBytes() */
        StringBuilder out = new StringBuilder();
        Reader reader = new FileReader(f);
        char[] buf = new char[100];
        int r;
        try {
            while ((r = reader.read(buf)) > 0) {
                out.append(buf, 0, r);
            }
        } finally {
            reader.close();
        }
        return out.toString();
    }
}
