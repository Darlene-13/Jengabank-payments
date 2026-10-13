#!/usr/bin/env bash
# Prints a BCrypt hash (cost 12, the same encoder the app uses) for a new or reset user password.
# The password is read from the terminal without echo, so it never lands in shell history.
#   ./scripts/hash-password.sh            -> prompts for the password
#   printf '%s' "$PW" | ./scripts/hash-password.sh
# Needs Java 21 and the Maven cache filled once by `mvn -q dependency:go-offline`.
set -euo pipefail

M2="${HOME}/.m2/repository/org/springframework"
CRYPTO=$(ls "$M2"/security/spring-security-crypto/6.*/spring-security-crypto-6.*.jar 2>/dev/null | grep -v -- -sources | sort -V | tail -1)
JCL=$(ls "$M2"/spring-jcl/6.*/spring-jcl-6.*.jar 2>/dev/null | grep -v -- -sources | sort -V | tail -1)
if [[ -z "$CRYPTO" || -z "$JCL" ]]; then
  echo "spring-security-crypto not in ~/.m2; run: mvn -q dependency:go-offline" >&2
  exit 1
fi

SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT
cat > "$SRC/Hash.java" <<'JAVA'
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class Hash {
    public static void main(String[] args) throws Exception {
        String password = System.console() != null
                ? new String(System.console().readPassword("Password: "))
                : new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (password == null || password.length() < 12) {
            System.err.println("Use at least 12 characters.");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder(12).encode(password));
    }
}
JAVA
java -cp "$CRYPTO:$JCL" "$SRC/Hash.java"
