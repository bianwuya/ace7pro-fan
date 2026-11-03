package dev.eta.acefan;
import java.util.*;
public final class CodeProfileTest {
    public static void main(String[] args)throws Exception {
        Map<String,String> reference=CodeProfileTool.hashes(args[0]);
        if(!reference.equals(ReferenceFanProfile.HASHES))throw new AssertionError("reference profile mismatch");
        Map<String,String> repacked=CodeProfileTool.hashes(args[1]);
        if(!repacked.equals(reference))throw new AssertionError("unrelated APK changes must not lock out identical policy");
        Map<String,String> changedCandidate=CodeProfileTool.hashes(args[2]);
        if(changedCandidate.equals(reference)||changedCandidate.get("candidate").equals(reference.get("candidate")))throw new AssertionError("candidate mutation not detected");
        Map<String,String> changedProtection=CodeProfileTool.hashes(args[3]);
        if(changedProtection.equals(reference)||changedProtection.get("downstream").equals(reference.get("downstream")))throw new AssertionError("protection mutation not detected");
        try {CodeProfileTool.hashes(args[4]);throw new AssertionError("malformed DEX accepted");} catch(java.io.IOException expected){}
        System.out.println("CodeProfile: 11 real reference bodies + APK repack + candidate/protection mutations + malformed DEX PASS");
    }
}
