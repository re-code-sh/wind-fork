/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package de.blinkt.openvpn.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters an API-supplied OpenVPN config before it is written to disk.
 *
 * The config is attacker controlled the moment anyone can tamper with the ServerConfigs response,
 * and the bundled engine is built with HAVE_FORK/HAVE_EXECVE, so directives like up/down/plugin
 * run commands as our own uid. Only directives on ALLOWED_DIRECTIVES survive; everything else is
 * dropped and reported.
 *
 * Scope, so the next reader does not assume more: this keeps a tampered config from reaching the
 * device, not from misdirecting traffic. The endpoint the app injects comes from the ServerList
 * response, and <ca>/<tls-auth> still arrive from the API, so anyone able to tamper with those can
 * still land a user on a server of their choosing. Closing that needs signed payloads or a pinned
 * node CA, not a bigger allowlist. Traffic steering is knowingly out of scope here.
 *
 * Tokenisation deliberately mirrors the engine's own parse_line(), bypass_doubledash() and
 * check_inline_file(), because what we match has to be what the engine acts on. Quoting, backslash
 * escapes and a leading double dash all reach add_option() as a bare directive name, so the quoted,
 * single-quoted, backslash-escaped and double-dashed spellings of "up" are one option to the engine
 * and have to be one option to us.
 */
public final class ServerConfigSanitizer {

    /**
     * Directives the server may set. Matching is case sensitive to mirror the engine's streq().
     *
     * Anything absent is dropped, so this list has to be validated against a real production
     * config before shipping, and extended in lockstep whenever the backend starts emitting a
     * new directive.
     *
     * Note what is deliberately absent: ca/cert/key/tls-auth/tls-crypt appear only in
     * ALLOWED_INLINE_TAGS below, so the inline form is accepted while the "ca /path/to/file"
     * form is not, and the server cannot point the engine at a local file.
     */
    private static final Set<String> ALLOWED_DIRECTIVES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    // remote/proto/port are deliberately absent: getConfigFile() injects its
                    // own "remote <ip> <port> <proto>" after the dev line, and it only ever
                    // appended. A server-supplied remote used to survive alongside it, and since
                    // multiple remotes form a connection list tried in order, one placed above the
                    // dev line was tried first. The app is now the only source of the endpoint.
                    "client", "dev", "dev-type", "float",
                    "nobind", "bind", "lport", "rport",
                    "resolv-retry", "connect-retry", "connect-retry-max", "connect-timeout",
                    "persist-key", "persist-tun",
                    "auth-user-pass", "static-challenge",
                    "remote-cert-tls", "remote-cert-eku", "verify-x509-name",
                    "cipher", "data-ciphers", "data-ciphers-fallback", "auth",
                    "tls-client", "tls-version-min", "tls-version-max",
                    "tls-cipher", "tls-ciphersuites",
                    "key-direction", "reneg-sec", "reneg-bytes", "reneg-pkts",
                    "hand-window", "tran-window",
                    "comp-lzo", "compress", "allow-compression",
                    "tun-mtu", "tun-mtu-extra", "mssfix", "fragment", "sndbuf", "rcvbuf",
                    "keepalive", "ping", "ping-restart", "ping-exit", "ping-timer-rem",
                    "explicit-exit-notify", "mute-replay-warnings", "replay-window",
                    "verb", "mute",
                    // Traffic steering. Legitimate for a VPN, but a hostile config server can use
                    // these to move DNS or routes while the UI still reads Connected. Drop them
                    // here if the client ever sets its own routes and DNS instead.
                    "pull", "route", "route-ipv6", "route-nopull",
                    "redirect-gateway", "redirect-private", "dhcp-option", "topology",
                    // Windscribe anti-censorship tweaks appended in VPNProfileCreator.
                    "udp-stuffing", "tcp-split-reset"
            )));

    /** Inline blocks the server may open. The body is copied through unread. */
    private static final Set<String> ALLOWED_INLINE_TAGS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs"
            )));

    private ServerConfigSanitizer() {
    }

    public static final class Result {
        public final String config;
        /** Directive names that were removed, for logging. Never contains argument values. */
        public final List<String> dropped;

        Result(String config, List<String> dropped) {
            this.config = config;
            this.dropped = Collections.unmodifiableList(dropped);
        }
    }

    public static Result sanitize(String rawConfig) {
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        // Split on any line ending. The server config is not obliged to use the platform's, and
        // splitting on line.separator alone leaves a stray CR on every token of a CRLF config.
        String[] lines = rawConfig.split("\r?\n", -1);

        String copyingTag = null;   // inside an allowed inline block
        String skippingTag = null;  // inside a rejected inline block

        for (String line : lines) {
            if (skippingTag != null) {
                if (isCloseTag(line, skippingTag)) {
                    skippingTag = null;
                }
                continue;
            }
            if (copyingTag != null) {
                kept.add(line);
                if (isCloseTag(line, copyingTag)) {
                    copyingTag = null;
                }
                continue;
            }

            String token = firstToken(line);
            if (token == null) {
                kept.add(line);     // blank or comment
                continue;
            }
            token = bypassDoubleDash(token);

            String tag = inlineTag(line);
            if (tag != null) {
                if (ALLOWED_INLINE_TAGS.contains(tag)) {
                    kept.add(line);
                    copyingTag = tag;
                } else {
                    dropped.add("<" + tag + ">");
                    skippingTag = tag;  // discard the body too, or it is read as directives
                }
                continue;
            }

            if (ALLOWED_DIRECTIVES.contains(token)) {
                kept.add(line);
            } else {
                dropped.add(token);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String l : kept) {
            sb.append(l).append("\n");
        }
        return new Result(sb.toString(), dropped);
    }

    /**
     * Returns the inline tag name if the line is an inline open tag, else null.
     *
     * Stricter than check_inline_file(), which accepts any single parameter wrapped in angle
     * brackets. Anything that does not match the plain tag form falls through to the directive
     * allowlist and is dropped there, so the stricter test only ever fails closed.
     */
    private static String inlineTag(String line) {
        String t = line.trim();
        if (t.length() < 3 || t.charAt(0) != '<' || t.charAt(t.length() - 1) != '>') {
            return null;
        }
        String name = t.substring(1, t.length() - 1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return name.isEmpty() ? null : name;
    }

    /** Mirrors read_inline_file(): left-trim, then prefix match, so a trailing suffix still closes. */
    private static boolean isCloseTag(String line, String tag) {
        int i = 0;
        while (i < line.length() && isSpaceChar(line.charAt(i))) {
            i++;
        }
        return line.startsWith("</" + tag + ">", i);
    }

    /** Mirrors bypass_doubledash(). */
    private static String bypassDoubleDash(String p) {
        return (p.length() >= 3 && p.startsWith("--")) ? p.substring(2) : p;
    }

    /** The engine's isspace() set. Kept to ASCII so the token boundary cannot drift from the C. */
    private static boolean isSpaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /**
     * Extracts the first parameter of a line exactly as parse_line() would, and returns null when
     * the line yields no parameter. Faithful port: same states, same backslash handling, same
     * treatment of the terminating NUL as whitespace.
     */
    static String firstToken(String line) {
        final int INITIAL = 0, QUOTED = 1, UNQUOTED = 2, DONE = 3, SQUOTED = 4;

        int state = INITIAL;
        boolean backslash = false;
        StringBuilder parm = new StringBuilder();

        int i = 0;
        while (true) {
            char in = i < line.length() ? line.charAt(i) : '\0';
            char out = 0;

            if (!backslash && in == '\\' && state != SQUOTED) {
                backslash = true;
            } else {
                if (state == INITIAL) {
                    if (in != '\0' && !isSpaceChar(in)) {
                        if (in == ';' || in == '#') {
                            break;      // comment: the rest of the line is not parsed
                        }
                        if (!backslash && in == '"') {
                            state = QUOTED;
                        } else if (!backslash && in == '\'') {
                            state = SQUOTED;
                        } else {
                            out = in;
                            state = UNQUOTED;
                        }
                    }
                } else if (state == UNQUOTED) {
                    if (!backslash && (in == '\0' || isSpaceChar(in))) {
                        state = DONE;
                    } else {
                        out = in;
                    }
                } else if (state == QUOTED) {
                    if (!backslash && in == '"') {
                        state = DONE;
                    } else {
                        out = in;
                    }
                } else if (state == SQUOTED) {
                    if (in == '\'') {
                        state = DONE;
                    } else {
                        out = in;
                    }
                }

                if (state == DONE) {
                    return parm.toString();
                }
                backslash = false;
            }

            if (out != 0) {
                parm.append(out);
            }
            if (i >= line.length()) {
                break;
            }
            i++;
        }
        return null;
    }
}
