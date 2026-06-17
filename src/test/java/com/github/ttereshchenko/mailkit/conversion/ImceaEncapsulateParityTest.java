package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.ttereshchenko.mailkit.pst.Message;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the deliberately-duplicated IMCEA encapsulation logic. {@link EmlSerializer} (plugin module)
 * and {@code Message} (the standalone {@code pst-parser} library, which cannot depend on the plugin)
 * each carry their own copy of {@code imceaEncapsulate}/{@code looksLikeSmtpAddress} with a "change
 * both together" comment. This asserts the two produce identical output over a shared vector set, so a
 * change made to one copy but not mirrored in the other fails the build.
 *
 * <p>The {@code pst-parser} copy is intentionally {@code private}; it is reached reflectively here
 * rather than widening its API purely for tests.
 */
class ImceaEncapsulateParityTest {

    private static final Method MESSAGE_IMCEA = privateStaticMethod("imceaEncapsulate", String.class, String.class);
    private static final Method MESSAGE_LOOKS_LIKE_SMTP = privateStaticMethod("looksLikeSmtpAddress", String.class);

    @Test
    void imceaEncapsulateMatchesAcrossModules() {
        // (addrType, address) pairs exercising every branch: passthrough (null/blank address,
        // SMTP-looking address, SMTP address type), the X.500-DN-without-addrType "EX" promotion, and
        // the char escaping (alnum and '-' kept, '/' -> '_', everything else -> _xXXXX_, including
        // spaces, '=' and non-ASCII).
        var vectors = List.of(
                new String[] {"EX", "/O=ORG/OU=First/CN=Recipients/CN=jdoe"},
                new String[] {"EX", "/O=ORG/CN=Jane Doe (Sales) =1="},
                new String[] {"", "/O=ORG/CN=USER"},
                new String[] {null, "/O=ORG/CN=USER"},
                new String[] {null, "plain-token-no-at"},
                new String[] {"", "still-no-at"},
                new String[] {"SMTP", "user@example.com"},
                new String[] {"smtp", "/O=ORG/CN=should-pass-through"},
                new String[] {"X400", "C=US;A= ;P=ORG;O=Unit;S=Doe;G=John;"},
                new String[] {"EX", "/O=ОРГ/CN=Юзер"},
                new String[] {"EX", null},
                new String[] {"EX", "   "},
                new String[] {"EX", "user@example.com"});
        for (var vector : vectors) {
            var addrType = vector[0];
            var address = vector[1];
            assertEquals(
                    EmlSerializer.imceaEncapsulate(addrType, address),
                    invoke(MESSAGE_IMCEA, addrType, address),
                    "imceaEncapsulate diverged for addrType=" + addrType + " address=" + address);
        }
    }

    @Test
    void looksLikeSmtpAddressMatchesAcrossModules() {
        var addresses = List.of(
                "user@example.com",
                "a@b",
                "no-at-sign",
                "@example.com",
                "user@",
                "a@b@c",
                "user name@example.com",
                "/O=x@y",
                "user<@example.com",
                "user>@example.com",
                "Юзер@例え.test");
        for (var address : addresses) {
            assertEquals(
                    EmlSerializer.looksLikeSmtpAddress(address),
                    invoke(MESSAGE_LOOKS_LIKE_SMTP, address),
                    "looksLikeSmtpAddress diverged for " + address);
        }
    }

    private static Method privateStaticMethod(String name, Class<?>... parameterTypes) {
        try {
            var method = Message.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            // A rename of the pst-parser twin must fail loudly — it still has to stay in sync.
            throw new AssertionError("Message." + name + " not found; the IMCEA twins must stay in sync", missing);
        }
    }

    private static Object invoke(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new AssertionError("Failed to invoke Message." + method.getName(), failure);
        }
    }
}
