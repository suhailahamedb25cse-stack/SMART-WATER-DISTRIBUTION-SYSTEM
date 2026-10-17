import com.sun.net.httpserver.HttpExchange;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Everything about logging in:
//  - passwords are never stored; only a salted hash is saved in the database
//  - after a correct login the server gives the browser a random "session token"
//    (in a cookie) and remembers who it belongs to
//  - too many wrong passwords lock that username for a few minutes
public class Auth {

    public static final String COOKIE_NAME = "WATER_SESSION";
    private static final long SESSION_MILLIS = 2 * 60 * 60 * 1000;   // logged out after 2 hours without use
    private static final int MAX_FAILED_TRIES = 5;
    private static final long LOCK_MILLIS = 5 * 60 * 1000;           // locked for 5 minutes

    // who is logged in on one browser
    public static class Session {
        public String username;
        public String fullName;
        public String role;       // OPERATOR or VIEWER
        public long lastUsed;
    }

    private static HashMap<String, Session> sessions = new HashMap<>();   // token -> session
    private static HashMap<String, long[]> failures = new HashMap<>();    // username -> {wrong tries, locked until}
    private static SecureRandom random = new SecureRandom();

    // ---------- accounts ----------

    // the first time the program runs, two accounts are created
    public static synchronized void createDefaultUsers() throws SQLException {
        if (Database.countUsers() == 0) {
            addUser("operator", "Water Office Operator", "OPERATOR", "operator123");
            addUser("viewer", "Municipal Officer", "VIEWER", "viewer123");
        }
    }

    private static void addUser(String username, String fullName, String role, String password) throws SQLException {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Database.insertUser(username, fullName, role, Base64.getEncoder().encodeToString(salt), hash(password, salt));
    }

    // turns a password into a hash that cannot be turned back into the password
    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Could not hash the password", e);
        }
    }

    // true if the password matches what is saved for this user
    private static boolean passwordMatches(String[] user, String password) {
        byte[] salt = Base64.getDecoder().decode(user[3]);
        String attempt = hash(password, salt);
        return MessageDigest.isEqual(attempt.getBytes(), user[4].getBytes());
    }

    // ---------- login / logout ----------

    // returns the new session token, or throws IllegalArgumentException with a message to show
    public static synchronized String login(String username, String password) throws SQLException {
        username = username == null ? "" : username.trim().toLowerCase();
        if (password == null) {
            password = "";
        }

        long now = System.currentTimeMillis();
        long[] failed = failures.get(username);
        if (failed != null && now < failed[1]) {
            long minutes = (failed[1] - now) / 60000 + 1;
            throw new IllegalArgumentException("Too many wrong attempts. Try again in " + minutes + " minute(s).");
        }

        String[] user = Database.findUser(username);
        boolean ok;
        if (user == null) {
            hash(password, new byte[16]);   // takes the same time, so nobody can tell if the username exists
            ok = false;
        } else {
            ok = passwordMatches(user, password);
        }

        if (!ok) {
            if (failed == null) {
                failed = new long[] { 0, 0 };
                failures.put(username, failed);
            }
            failed[0]++;
            if (failed[0] >= MAX_FAILED_TRIES) {
                failed[0] = 0;
                failed[1] = now + LOCK_MILLIS;
            }
            throw new IllegalArgumentException("Wrong username or password.");
        }

        failures.remove(username);

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Session s = new Session();
        s.username = user[0];
        s.fullName = user[1];
        s.role = user[2];
        s.lastUsed = now;
        sessions.put(token, s);
        return token;
    }

    // finds out who is making this request (null = not logged in)
    public static synchronized Session check(HttpExchange ex) {
        String token = getToken(ex);
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastUsed > SESSION_MILLIS) {
            sessions.remove(token);
            return null;
        }
        s.lastUsed = now;
        return s;
    }

    // looks up a session by its token (used right after logging in)
    public static synchronized Session getSession(String token) {
        return sessions.get(token);
    }

    public static synchronized void logout(HttpExchange ex) {
        String token = getToken(ex);
        if (token != null) {
            sessions.remove(token);
        }
    }

    // reads our cookie from the request
    private static String getToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE_NAME)) {
                return kv[1];
            }
        }
        return null;
    }

    // ---------- change password ----------

    public static synchronized void changePassword(String username, String oldPassword, String newPassword) throws SQLException {
        String[] user = Database.findUser(username);
        if (user == null || oldPassword == null || !passwordMatches(user, oldPassword)) {
            throw new IllegalArgumentException("The current password is wrong.");
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new IllegalArgumentException("The new password must be 6 to 64 characters long.");
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalArgumentException("The new password must be different from the old one.");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Database.updatePassword(username, Base64.getEncoder().encodeToString(salt), hash(newPassword, salt));
    }
}
