package com.sun.akuma.test;
import java.io.IOException;

import org.junit.Assert;
import junit.framework.TestCase;

import com.sun.akuma.JavaVMArguments;


public class ArgvTest extends TestCase {
	
	public void testSupportByOS() throws IOException{
		try{
			JavaVMArguments.current();
		}catch(UnsupportedOperationException e){
			Assert.fail("Your OS isn't supported by Akuma");
		}
	}
	
	public void testJavaIsFirstArg() throws IOException{
		try{
			JavaVMArguments args = JavaVMArguments.current();
			Assert.assertTrue(args.size() > 1);
			Assert.assertTrue(args.get(0).endsWith("java"));
		}catch(UnsupportedOperationException e){
			Assert.fail("Your OS isn't supported by Akuma");
		}
	}
	
}
