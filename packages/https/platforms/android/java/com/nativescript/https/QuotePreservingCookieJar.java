package com.nativescript.https;
/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.WARN;

/** A cookie jar that delegates to a {@link java.net.CookieHandler}. */
public final class QuotePreservingCookieJar implements CookieJar {
  private final CookieHandler cookieHandler;

  public QuotePreservingCookieJar(CookieHandler cookieHandler) {
    this.cookieHandler = cookieHandler;
  }

  @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
    if (cookieHandler != null) {
      List<String> cookieStrings = new ArrayList<>();
      for (Cookie cookie : cookies) {
        cookieStrings.add(cookie.toString().replaceAll("; domain=", "; domain=."));
      }
      Map<String, List<String>> multimap = Collections.singletonMap("Set-Cookie", cookieStrings);
      try {
        cookieHandler.put(url.uri(), multimap);
      } catch (IOException e) {
        // Platform.get().log(WARN, "Saving cookies failed for " + url.resolve("/..."), e);
      }
    }
  }

  @Override public List<Cookie> loadForRequest(HttpUrl url) {
    // The RI passes all headers. We don't have 'em, so we don't pass 'em!
    Map<String, List<String>> headers = Collections.emptyMap();
    Map<String, List<String>> cookieHeaders;
    try {
      cookieHeaders = cookieHandler.get(url.uri(), headers);
    } catch (IOException e) {
      // Platform.get().log(WARN, "Loading cookies failed for " + url.resolve("/..."), e);
      return Collections.emptyList();
    }

    List<Cookie> cookies = null;
    for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
      String key = entry.getKey();
      if (("Cookie".equalsIgnoreCase(key) || "Cookie2".equalsIgnoreCase(key))
          && !entry.getValue().isEmpty()) {
        for (String header : entry.getValue()) {
          if (cookies == null) cookies = new ArrayList<>();
          cookies.addAll(decodeHeaderAsJavaNetCookies(url, header));
        }
      }
    }

    return cookies != null
        ? Collections.unmodifiableList(cookies)
        : Collections.<Cookie>emptyList();
  }

  /**
   * Increments {@code pos} until {@code input[pos]} is not ASCII whitespace. Stops at {@code
   * limit}.
   */
  public static int skipLeadingAsciiWhitespace(String input, int pos, int limit) {
    for (int i = pos; i < limit; i++) {
      switch (input.charAt(i)) {
        case '\t':
        case '\n':
        case '\f':
        case '\r':
        case ' ':
          continue;
        default:
          return i;
      }
    }
    return limit;
  }

  /**
   * Decrements {@code limit} until {@code input[limit - 1]} is not ASCII whitespace. Stops at
   * {@code pos}.
   */
  public static int skipTrailingAsciiWhitespace(String input, int pos, int limit) {
    for (int i = limit - 1; i >= pos; i--) {
      switch (input.charAt(i)) {
        case '\t':
        case '\n':
        case '\f':
        case '\r':
        case ' ':
          continue;
        default:
          return i + 1;
      }
    }
    return pos;
  }
  
   /** Equivalent to {@code string.substring(pos, limit).trim()}. */
  public static String trimSubstring(String string, int pos, int limit) {
    int start = skipLeadingAsciiWhitespace(string, pos, limit);
    int end = skipTrailingAsciiWhitespace(string, start, limit);
    return string.substring(start, end);
  }

  /**
   * Returns the index of the first character in {@code input} that contains a character in {@code
   * delimiters}. Returns limit if there is no such character.
   */
  public static int delimiterOffset(String input, int pos, int limit, String delimiters) {
    for (int i = pos; i < limit; i++) {
      if (delimiters.indexOf(input.charAt(i)) != -1) return i;
    }
    return limit;
  }

  /**
   * Convert a request header to OkHttp's cookies via {@link HttpCookie}. That extra step handles
   * multiple cookies in a single request header, which {@link Cookie#parse} doesn't support.
   */
  private List<Cookie> decodeHeaderAsJavaNetCookies(HttpUrl url, String header) {
    List<Cookie> result = new ArrayList<>();
    for (int pos = 0, limit = header.length(), pairEnd; pos < limit; pos = pairEnd + 1) {
      pairEnd = delimiterOffset(header, pos, limit, ";,");
      int equalsSign = delimiterOffset(header, pos, pairEnd, "=");
      String name = trimSubstring(header, pos, equalsSign);
      if (name.startsWith("$")) continue;

      // We have either name=value or just a name.
      String value = equalsSign < pairEnd
          ? trimSubstring(header, equalsSign + 1, pairEnd)
          : "";

      result.add(new Cookie.Builder()
          .name(name)
          .value(value)
          .domain(url.host())
          .build());
    }
    return result;
  }
}