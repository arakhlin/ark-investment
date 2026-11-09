package com.ark.arkinvestmentapi;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestRequests {
    private TestRequests() {}
    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.with(jwt().jwt(j -> j.issuer("https://issuer.test").subject("test-admin")));
    }
    public static MockHttpServletRequestBuilder get(String path, Object... args) { return auth(MockMvcRequestBuilders.get(path,args)); }
    public static MockHttpServletRequestBuilder post(String path, Object... args) { return auth(MockMvcRequestBuilders.post(path,args)); }
    public static MockHttpServletRequestBuilder put(String path, Object... args) { return auth(MockMvcRequestBuilders.put(path,args)); }
    public static MockHttpServletRequestBuilder delete(String path, Object... args) { return auth(MockMvcRequestBuilders.delete(path,args)); }
}
