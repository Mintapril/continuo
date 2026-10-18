package dev.dsh.pocket.cowork.broker;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Mutual request-bound authentication. Reusable secrets never cross the loopback socket. */
public final class BrokerAuth {
    public static final long TTL_MS=30_000;
    private final String agent,controller;
    private final LongSupplier clock;
    private final SecureRandom random=new SecureRandom();
    private final LinkedHashMap<String,Challenge> pending=new LinkedHashMap<>();
    private record Challenge(String role,String clientNonce,String serverNonce,String digest,long at) {}
    public record Authenticated(String role,String clientNonce,String serverNonce,String digest,String secret) {
        public boolean controller(){return role.equals("controller");}
        public String responseProof(int status,byte[] body){return mac(secret,"response\n"+role+"\n"+clientNonce+"\n"+serverNonce+"\n"+digest+"\n"+status+"\n"+sha256(body));}
        @Override public String toString(){return "Authenticated[redacted]";}
    }
    public BrokerAuth(String agent,String controller,LongSupplier clock){this.agent=agent;this.controller=controller;this.clock=clock;}
    public synchronized JSONObject challenge(JSONObject request)throws Exception{
        if(request.length()!=3||!(request.opt("role") instanceof String)||!(request.opt("clientNonce") instanceof String)||!(request.opt("digest") instanceof String))throw new SecurityException("Invalid authentication challenge");
        String role=request.getString("role"),cn=request.getString("clientNonce"),digest=request.getString("digest");
        if((!role.equals("agent")&&!role.equals("controller"))||!hex(cn)||!hex(digest))throw new SecurityException("Invalid authentication challenge");
        long now=clock.getAsLong();pending.values().removeIf(p->now<p.at()||now-p.at()>TTL_MS);
        byte[] nonce=new byte[32];random.nextBytes(nonce);String sn=toHex(nonce);
        Challenge p=new Challenge(role,cn,sn,digest,now);pending.put(sn,p);while(pending.size()>128)pending.remove(pending.keySet().iterator().next());
        return new JSONObject().put("serverNonce",sn).put("proof",mac(secret(role),message("server",p)));
    }
    public synchronized Authenticated authenticate(String header,String method,String path,byte[] body){
        if(!header.startsWith("Cowork-HMAC "))throw new SecurityException("Authentication required");
        String[] parts=header.substring(12).split(":",-1);
        if(parts.length!=4||!hex(parts[1])||!hex(parts[2])||!hex(parts[3]))throw new SecurityException("Invalid authentication");
        Challenge p=pending.remove(parts[2]);long now=clock.getAsLong();
        if(p==null||!p.role().equals(parts[0])||!p.clientNonce().equals(parts[1])||now<p.at()||now-p.at()>TTL_MS||!p.digest().equals(digest(method,path,body))||!equal(parts[3],mac(secret(p.role()),message("client",p))))throw new SecurityException("Invalid authentication");
        return new Authenticated(p.role(),p.clientNonce(),p.serverNonce(),p.digest(),secret(p.role()));
    }
    private String secret(String role){return role.equals("controller")?controller:agent;}
    private static String message(String domain,Challenge p){return domain+"\n"+p.role()+"\n"+p.clientNonce()+"\n"+p.serverNonce()+"\n"+p.digest();}
    public static String digest(String method,String path,byte[] body){try{MessageDigest md=MessageDigest.getInstance("SHA-256");md.update((method+"\n"+path+"\n").getBytes(StandardCharsets.UTF_8));return toHex(md.digest(body));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    public static String sha256(byte[] body){try{return toHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    public static String mac(String secret,String message){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    public static boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    private static boolean hex(String s){return s.matches("[0-9a-f]{64}");}
    private static String toHex(byte[] data){StringBuilder b=new StringBuilder(data.length*2);for(byte value:data)b.append(String.format(Locale.ROOT,"%02x",value&255));return b.toString();}
}
