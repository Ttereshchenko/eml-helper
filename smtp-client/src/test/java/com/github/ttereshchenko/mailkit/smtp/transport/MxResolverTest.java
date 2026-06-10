package com.github.ttereshchenko.mailkit.smtp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.junit.jupiter.api.Test;

class MxResolverTest {

    @Test
    void resolveOrdersByPreferenceAscending() throws Exception {
        var attribute = new BasicAttribute("MX");
        attribute.add("20 mx2.example.com.");
        attribute.add("10 mx1.example.com.");
        attribute.add("30 mx3.example.com.");
        var fixture = new FixtureContext(Map.of("example.com", attribute));

        var resolved = new MxResolver(() -> fixture).resolve("example.com");

        assertEquals(List.of("mx1.example.com", "mx2.example.com", "mx3.example.com"), resolved);
        assertTrue(fixture.closed, "context must be closed");
    }

    @Test
    void resolveStripsTrailingDot() throws Exception {
        var attribute = new BasicAttribute("MX");
        attribute.add("10 mx.example.com.");
        var fixture = new FixtureContext(Map.of("example.com", attribute));

        var resolved = new MxResolver(() -> fixture).resolve("example.com");

        assertEquals(List.of("mx.example.com"), resolved);
    }

    @Test
    void resolveReturnsEmptyListWhenNoMxRecord() throws Exception {
        var fixture = new FixtureContext(Map.of());

        var resolved = new MxResolver(() -> fixture).resolve("nowhere.example.com");

        assertEquals(List.of(), resolved);
    }

    @Test
    void resolveIgnoresMalformedEntries() throws Exception {
        var attribute = new BasicAttribute("MX");
        attribute.add("not-a-number mx.example.com.");
        attribute.add("malformed");
        attribute.add("10 mx.example.com.");
        var fixture = new FixtureContext(Map.of("example.com", attribute));

        var resolved = new MxResolver(() -> fixture).resolve("example.com");

        assertEquals(List.of("mx.example.com"), resolved);
    }

    @Test
    void nullMxRecordSurfacesAsEmptyHostname() throws Exception {
        // rfc7505: "0 ." means the domain accepts no mail; the resolver exposes it as "" so the
        // client can refuse to send instead of dialing an empty host.
        var attribute = new BasicAttribute("MX");
        attribute.add("0 .");
        var fixture = new FixtureContext(Map.of("example.com", attribute));

        var resolved = new MxResolver(() -> fixture).resolve("example.com");

        assertEquals(List.of(""), resolved);
    }

    @Test
    void equalPreferenceRecordsAreAllPresentAndPrecedeWorsePreferences() throws Exception {
        // Equal-preference records are shuffled (rfc5321 §5.1 load spreading), so assert set
        // membership per preference tier rather than a fixed order.
        var attribute = new BasicAttribute("MX");
        attribute.add("10 mxa.example.com.");
        attribute.add("10 mxb.example.com.");
        attribute.add("20 backup.example.com.");
        var fixture = new FixtureContext(Map.of("example.com", attribute));

        var resolved = new MxResolver(() -> fixture).resolve("example.com");

        assertEquals(3, resolved.size());
        assertEquals("backup.example.com", resolved.get(2));
        assertTrue(
                resolved.subList(0, 2).containsAll(List.of("mxa.example.com", "mxb.example.com")), resolved.toString());
    }

    /** Minimal DirContext stub — only {@code getAttributes(String, String[])} is exercised. */
    private static final class FixtureContext implements DirContext {

        private final Map<String, Attribute> records;
        private boolean closed;

        FixtureContext(Map<String, Attribute> records) {
            this.records = records;
        }

        @Override
        public Attributes getAttributes(String name, String[] attrIds) {
            var attribute = records.get(name);
            var result = new BasicAttributes(true);
            if (attribute != null) {
                result.put(attribute);
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }

        // The rest of the DirContext surface is unused by MxResolver; throwing keeps any
        // future misuse loud rather than silently returning misleading data.
        @Override
        public Attributes getAttributes(Name name) {
            return unsupported();
        }

        @Override
        public Attributes getAttributes(String name) {
            return unsupported();
        }

        @Override
        public Attributes getAttributes(Name name, String[] attrIds) {
            return unsupported();
        }

        @Override
        public void modifyAttributes(Name name, int modOp, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public void modifyAttributes(String name, int modOp, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public void modifyAttributes(Name name, ModificationItem[] mods) {
            throw unsupportedOp();
        }

        @Override
        public void modifyAttributes(String name, ModificationItem[] mods) {
            throw unsupportedOp();
        }

        @Override
        public void bind(Name name, Object obj, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public void bind(String name, Object obj, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public void rebind(Name name, Object obj, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public void rebind(String name, Object obj, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public DirContext createSubcontext(Name name, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public DirContext createSubcontext(String name, Attributes attrs) {
            throw unsupportedOp();
        }

        @Override
        public DirContext getSchema(Name name) {
            throw unsupportedOp();
        }

        @Override
        public DirContext getSchema(String name) {
            throw unsupportedOp();
        }

        @Override
        public DirContext getSchemaClassDefinition(Name name) {
            throw unsupportedOp();
        }

        @Override
        public DirContext getSchemaClassDefinition(String name) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(
                Name name, Attributes matchingAttributes, String[] attributesToReturn) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(
                String name, Attributes matchingAttributes, String[] attributesToReturn) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(Name name, Attributes matchingAttributes) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(String name, Attributes matchingAttributes) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(Name name, String filter, SearchControls cons) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(String name, String filter, SearchControls cons) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(
                Name name, String filterExpr, Object[] filterArgs, SearchControls cons) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<SearchResult> search(
                String name, String filterExpr, Object[] filterArgs, SearchControls cons) {
            throw unsupportedOp();
        }

        @Override
        public Object lookup(Name name) {
            throw unsupportedOp();
        }

        @Override
        public Object lookup(String name) {
            throw unsupportedOp();
        }

        @Override
        public void bind(Name name, Object obj) {
            throw unsupportedOp();
        }

        @Override
        public void bind(String name, Object obj) {
            throw unsupportedOp();
        }

        @Override
        public void rebind(Name name, Object obj) {
            throw unsupportedOp();
        }

        @Override
        public void rebind(String name, Object obj) {
            throw unsupportedOp();
        }

        @Override
        public void unbind(Name name) {
            throw unsupportedOp();
        }

        @Override
        public void unbind(String name) {
            throw unsupportedOp();
        }

        @Override
        public void rename(Name oldName, Name newName) {
            throw unsupportedOp();
        }

        @Override
        public void rename(String oldName, String newName) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<javax.naming.NameClassPair> list(Name name) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<javax.naming.NameClassPair> list(String name) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<javax.naming.Binding> listBindings(Name name) {
            throw unsupportedOp();
        }

        @Override
        public NamingEnumeration<javax.naming.Binding> listBindings(String name) {
            throw unsupportedOp();
        }

        @Override
        public void destroySubcontext(Name name) {
            throw unsupportedOp();
        }

        @Override
        public void destroySubcontext(String name) {
            throw unsupportedOp();
        }

        @Override
        public javax.naming.Context createSubcontext(Name name) {
            throw unsupportedOp();
        }

        @Override
        public javax.naming.Context createSubcontext(String name) {
            throw unsupportedOp();
        }

        @Override
        public Object lookupLink(Name name) {
            throw unsupportedOp();
        }

        @Override
        public Object lookupLink(String name) {
            throw unsupportedOp();
        }

        @Override
        public javax.naming.NameParser getNameParser(Name name) {
            throw unsupportedOp();
        }

        @Override
        public javax.naming.NameParser getNameParser(String name) {
            throw unsupportedOp();
        }

        @Override
        public Name composeName(Name name, Name prefix) {
            throw unsupportedOp();
        }

        @Override
        public String composeName(String name, String prefix) {
            throw unsupportedOp();
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal) {
            throw unsupportedOp();
        }

        @Override
        public Object removeFromEnvironment(String propName) {
            throw unsupportedOp();
        }

        @Override
        public Hashtable<?, ?> getEnvironment() {
            throw unsupportedOp();
        }

        @Override
        public String getNameInNamespace() {
            throw unsupportedOp();
        }

        private static Attributes unsupported() {
            throw unsupportedOp();
        }

        private static UnsupportedOperationException unsupportedOp() {
            return new UnsupportedOperationException("not implemented in test fixture");
        }
    }
}
