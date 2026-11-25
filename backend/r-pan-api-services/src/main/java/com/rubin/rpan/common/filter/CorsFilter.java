package com.rubin.rpan.common.filter;

import com.rubin.rpan.util.HttpUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** 跨域设置 */
@Component
public class CorsFilter implements Filter {

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletResponse response = (HttpServletResponse) res;
    HttpUtil.addCorsResponseHeader(response);
    chain.doFilter(req, res);
  }
}
