package com.lrj.trade.server.console;

import com.sun.net.httpserver.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/** 仅测试classpath中的隔离OIDC签发方；实际执行S256与RSA，不替换生产JwtDecoder。 */
public final class ConsoleTestIdentity implements AutoCloseable {
    private final HttpServer server;
    private final RSAKey key;
    private final String redirect;
    private final Map<String,Map<String,String>> pending=new ConcurrentHashMap<>();
    private final Map<String,Map<String,String>> codes=new ConcurrentHashMap<>();
    private final JsonMapper json=JsonMapper.builder().build();
    /** 绑定回环随机端口，唯一回调地址由本轮测试分配。 */
    public ConsoleTestIdentity(int frontendPort) throws Exception {
        redirect="http://127.0.0.1:"+frontendPort+"/auth/callback";
        key=new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::handle);server.start();
    }
    /** 返回真实测试issuer，供前后端同时使用。 */
    public String issuer() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    /** 仅供HTTP边界断言；浏览器必须经授权码回调获取自己的令牌。 */
    public String accessToken(String role) throws Exception { return sign(role,null); }
    private String sign(String role,String nonce) throws Exception {
        long tenant=role.equals("other")?900002:900001;
        String scope=switch(role) {
            case "reader" -> "openid profile";
            case "diagnostic" -> "openid profile trade.admin trade.admin.evidence.read trade.admin.jobs.read trade.return.write trade.fulfillment.write";
            default -> "openid profile trade.admin trade.return.write trade.fulfillment.write";
        };
        var b=new JWTClaimsSet.Builder().issuer(issuer()).audience("transaction-center").subject(role)
            .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .claim("tenant_id",Long.toString(tenant)).claim("scope",scope).claim("name","联调"+role);
        if(nonce!=null) b.claim("nonce",nonce);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),b.build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    private void handle(HttpExchange exchange) {
        try {
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(origin!=null && origin.equals(redirect.substring(0,redirect.indexOf("/auth/")))) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin",origin);
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers","content-type");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,OPTIONS");
            }
            if(exchange.getRequestMethod().equals("OPTIONS")) {exchange.sendResponseHeaders(204,-1);exchange.close();return;}
            String path=exchange.getRequestURI().getPath();
            Map<String,String> q=parse(exchange.getRequestURI().getRawQuery());
            if(path.equals("/.well-known/openid-configuration")) {
                send(exchange,200,Map.of("issuer",issuer(),"authorization_endpoint",issuer()+"/authorize",
                    "token_endpoint",issuer()+"/token","jwks_uri",issuer()+"/jwks","response_types_supported",List.of("code"),
                    "subject_types_supported",List.of("public"),"id_token_signing_alg_values_supported",List.of("RS256"),
                    "code_challenge_methods_supported",List.of("S256")));
            } else if(path.equals("/jwks")) send(exchange,200,new JWKSet(key.toPublicJWK()).toJSONObject());
            else if(path.equals("/authorize")) {
                if(!"transaction-center".equals(q.get("client_id")) || !redirect.equals(q.get("redirect_uri"))
                    || !"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method"))
                    || q.get("code_challenge")==null || q.get("state")==null) {send(exchange,400,Map.of("error","invalid_request"));return;}
                String id=UUID.randomUUID().toString();pending.put(id,q);
                byte[] body=("<html lang='zh'><meta charset='UTF-8'><title>隔离测试身份</title><h1>隔离测试身份</h1>"
                    +"<form method='post' action='/login'><input type='hidden' name='request' value='"+id+"'>"
                    +"<label>测试角色<select name='role'><option>admin</option><option>diagnostic</option><option>reader</option><option>other</option></select></label>"
                    +"<button>签发测试授权码</button></form></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            } else if(path.equals("/login") && exchange.getRequestMethod().equals("POST")) {
                var form=parse(new String(exchange.getRequestBody().readNBytes(8192),StandardCharsets.UTF_8));
                var req=pending.remove(form.getOrDefault("request",""));String role=form.get("role");
                if(req==null || !Set.of("admin","diagnostic","reader","other").contains(role)) {send(exchange,400,Map.of("error","invalid_request"));return;}
                req.put("role",role);String code=UUID.randomUUID().toString();codes.put(code,req);
                exchange.getResponseHeaders().set("Location",redirect+"?code="+code+"&state="+URLEncoder.encode(req.get("state"),StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(302,-1);exchange.close();
            } else if(path.equals("/token") && exchange.getRequestMethod().equals("POST")) {
                var form=parse(new String(exchange.getRequestBody().readNBytes(8192),StandardCharsets.UTF_8));
                var req=codes.remove(form.getOrDefault("code",""));
                String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.getOrDefault("code_verifier","").getBytes(StandardCharsets.US_ASCII)));
                if(req==null || !redirect.equals(form.get("redirect_uri")) || !"authorization_code".equals(form.get("grant_type"))
                    || !"transaction-center".equals(form.get("client_id")) || !challenge.equals(req.get("code_challenge"))) {
                    send(exchange,400,Map.of("error","invalid_grant"));return;
                }
                send(exchange,200,Map.of("access_token",sign(req.get("role"),null),"id_token",sign(req.get("role"),req.get("nonce")),
                    "token_type","Bearer","expires_in",900,"scope","openid profile"));
            } else send(exchange,404,Map.of("error","not_found"));
        } catch(Exception error) {
            try {send(exchange,500,Map.of("error","test_identity_error"));} catch(Exception ignored) {exchange.close();}
        }
    }
    private Map<String,String> parse(String query) {
        Map<String,String> result=new HashMap<>();if(query==null) return result;
        for(String entry:query.split("&")) {String[] pair=entry.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8));}
        return result;
    }
    private void send(HttpExchange e,int status,Object value) throws Exception {
        byte[] data=json.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");
        e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();
    }
    /** 关闭本轮专属监听，不触碰Casdoor或共享身份系统。 */
    @Override public void close() {server.stop(0);}
}
