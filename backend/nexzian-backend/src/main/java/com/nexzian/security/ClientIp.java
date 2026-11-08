package com.nexzian.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single place that decides which IP address the rate limiters attribute a request to.
 *
 * It uses ONLY {@code request.getRemoteAddr()} - the address of the TCP peer - and never
 * reads X-Forwarded-For, Forwarded or X-Real-IP itself, because any client can send those
 * headers and would otherwise be able to pick its own "IP" and bypass every limit.
 *
 * <h3>Production deployment requirement (reverse proxy / load balancer)</h3>
 * Behind a proxy, the TCP peer is the PROXY, so every user would share one address and be
 * throttled together. When Nexzian is deployed behind a proxy you must configure the
 * server to rewrite the remote address from the forwarded header, and only for proxies you
 * trust, for example in application.properties:
 * <pre>
 * server.forward-headers-strategy=native
 * server.tomcat.remoteip.internal-proxies=10\.0\.0\.\d{1,3}   # your proxy/LB addresses only
 * </pre>
 * Tomcat then replaces {@code getRemoteAddr()} with the client address taken from
 * X-Forwarded-For ONLY when the request really came from a trusted proxy, and ignores the
 * header otherwise. The proxy itself must also overwrite (not append to) any
 * X-Forwarded-For sent by the client. Until that is configured, do not deploy behind a
 * proxy with these limits enabled.
 */
public final class ClientIp {

    private ClientIp() { }

    public static String of(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
