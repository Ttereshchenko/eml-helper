package com.github.ttereshchenko.mailkit.smtp.transport;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * Performs DNS MX lookups via JDK's {@code dns:} JNDI provider — no external dependency.
 * Records are sorted by preference (lower wins) per RFC 5321 §5. Used when the caller opts into
 * {@code TransportConfig.withMxRouting(true)} (swaks's {@code --copy-routing}): the destination
 * host is then chosen from the {@code MAIL FROM} domain rather than the static profile host.
 */
public final class MxResolver {

    @FunctionalInterface
    public interface DirContextFactory {
        DirContext create() throws NamingException;
    }

    private final DirContextFactory contextFactory;

    public MxResolver() {
        this(MxResolver::defaultContext);
    }

    public MxResolver(DirContextFactory contextFactory) {
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    }

    /**
     * Returns the MX hostnames for {@code domain} ordered from highest priority (lowest
     * preference number) first. An empty list means no MX records — RFC 5321 §5 says the client
     * should then fall back to the A/AAAA of the domain itself; callers are responsible for that.
     */
    public List<String> resolve(String domain) throws NamingException {
        Objects.requireNonNull(domain, "domain");
        var context = contextFactory.create();
        try {
            var attributes = context.getAttributes(domain, new String[] {"MX"});
            return extractMxHostnames(attributes);
        } finally {
            context.close();
        }
    }

    private static List<String> extractMxHostnames(Attributes attributes) throws NamingException {
        var mxAttribute = attributes.get("MX");
        if (mxAttribute == null) {
            return List.of();
        }
        var raw = new ArrayList<Record>();
        for (var index = 0; index < mxAttribute.size(); index++) {
            var line = String.valueOf(mxAttribute.get(index)).trim();
            var split = line.split("\\s+", 2);
            if (split.length != 2) {
                continue;
            }
            int preference;
            try {
                preference = Integer.parseInt(split[0]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            var host = split[1];
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            raw.add(new Record(preference, host));
        }
        raw.sort((left, right) -> Integer.compare(left.preference(), right.preference()));
        var ordered = new ArrayList<String>(raw.size());
        for (var record : raw) {
            ordered.add(record.host());
        }
        return ordered;
    }

    private record Record(int preference, String host) {}

    private static DirContext defaultContext() throws NamingException {
        var environment = new Hashtable<String, Object>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        return new InitialDirContext(environment);
    }
}
