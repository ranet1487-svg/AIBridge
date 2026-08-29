package ai.aibridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline cyber-safety guardrail for the AI Bridge.
 *
 * Pipeline per prompt:
 *   1. detect category / intent (recon, sqli, xss, creds, phishing, malware,
 *      dos, mitm, privesc, social, wifi, defense, learning)
 *   2. detect scope (third-party / unauthorized vs own lab / CTF / authorized)
 *   3. detect intent signals: defense-boost, learning-verb, action-verb
 *   4. assign a risk level and, when needed, rewrite the prompt into a safe,
 *      educational + defensive form BEFORE forwarding to the AI.
 *
 * Risk levels:
 *   0 SAFE        - theory / defense / own-device general questions (as-is)
 *   1 EDUCATIONAL - explicitly authorized scope (own lab / CTF / DVWA ...)
 *   2 CAUTION     - ambiguous offensive intent -> rewritten to educational
 *   3 UNSAFE      - explicit third-party / unauthorized target -> rewritten,
 *                   original target stripped, authorized-scope substituted
 *   4 BLOCKED     - clearly illegal / severe-harm content -> NEVER forwarded;
 *                   a refusal with a legal note is returned instead
 *
 * The engine is deterministic and has NO external dependencies (no network,
 * no Android APIs) so it can be unit-tested on a plain JVM.
 */
public final class CyberSafetyEngine {

    public static final int SAFE = 0;
    public static final int EDUCATIONAL = 1;
    public static final int CAUTION = 2;
    public static final int UNSAFE = 3;
    public static final int BLOCKED = 4;

    /** Outcome of classifying one prompt. */
    public static final class Verdict {
        public int level;
        public String category = "learning";
        public List<String> tags = new ArrayList<>();
        public boolean rewritten;
        public boolean blocked;
        public String safePrompt;      // non-null when level >= 2
        public String noteEn = "";
        public String noteHi = "";
        public String original = "";

        public String toJson() {
            StringBuilder b = new StringBuilder();
            b.append("{")
             .append("\"level\":").append(level).append(",")
             .append("\"category\":").append(jq(category)).append(",")
             .append("\"rewritten\":").append(rewritten).append(",")
             .append("\"blocked\":").append(blocked).append(",")
             .append("\"tags\":[");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) b.append(",");
                b.append(jq(tags.get(i)));
            }
            b.append("],")
             .append("\"safe_prompt\":").append(safePrompt == null ? "null" : jq(safePrompt)).append(",")
             .append("\"note_en\":").append(jq(noteEn)).append(",")
             .append("\"note_hi\":").append(jq(noteHi))
             .append("}");
            return b.toString();
        }
    }

    // ---- category keyword tables (lowercased, matched as substrings) ----
    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("recon", new String[]{"nmap","port scan","scan ","enumerate","recon","subdomain","discover host","network mapper"});
        CATEGORIES.put("sqli", new String[]{"sql injection","sql inject","sqlmap","' or 1=1","sqli","blind sql"});
        CATEGORIES.put("xss", new String[]{"cross-site scripting","xss","<script","onerror=","alert(","dom xss","stored xss"});
        CATEGORIES.put("creds", new String[]{"password crack","cracking","hashcat","john the ripper","brute force","bruteforce","hydra","wordlist","credential","rockyou","rainbow table"});
        CATEGORIES.put("phishing", new String[]{"phishing","phish","spoof login","fake login","credential harvest","harvest credentials","clone site","clone a site"});
        CATEGORIES.put("malware", new String[]{"ransomware","keylogger","trojan","virus","worm","reverse shell","bind shell","payload","malware","backdoor"," botnet","rat ","remote access trojan"});
        CATEGORIES.put("dos", new String[]{"ddos","denial of service","dos attack","flood ","syn flood","botnet attack","overload server","take down"});
        CATEGORIES.put("mitm", new String[]{"man in the middle","man-in-the-middle","arp spoof","arp poison","ssl strip","packet sniff","sniff traffic","mitm"});
        CATEGORIES.put("privesc", new String[]{"privilege escalation","privesc","buffer overflow","exploit","root ","jailbreak","escalate privileges","0day","0-day","rce","remote code execution"});
        CATEGORIES.put("social", new String[]{"social engineering","pretext","impersonate","manipulate user","tailgating","baiting"});
        CATEGORIES.put("wifi", new String[]{"wifi hack","wpa","wps","handshake","deauth","de-authenticate","aircrack","wifite","wpa2","crack wifi","router hack","wifi password"});
        CATEGORIES.put("defense", new String[]{"firewall","hardening","harden","patch","secure","defend","mitigate","detect","monitor","protect","defense","defensive","sandbox","encryption"});
        CATEGORIES.put("learning", new String[]{"explain","what is","what's","how does","how do","tutorial","learn","concept","theory","example of","difference between","guide"});
    }

    private static final Map<String, String> CONCEPT = new LinkedHashMap<>();
    static {
        CONCEPT.put("recon", "network/port reconnaissance and attack-surface mapping");
        CONCEPT.put("sqli", "SQL injection");
        CONCEPT.put("xss", "cross-site scripting (XSS)");
        CONCEPT.put("creds", "credential attacks (brute force, password cracking, hash attacks)");
        CONCEPT.put("phishing", "phishing and credential harvesting");
        CONCEPT.put("malware", "malware, trojans, keyloggers and reverse shells");
        CONCEPT.put("dos", "denial-of-service / DDoS");
        CONCEPT.put("mitm", "man-in-the-middle attacks (ARP spoofing, SSL stripping)");
        CONCEPT.put("privesc", "privilege escalation and exploit development");
        CONCEPT.put("social", "social engineering");
        CONCEPT.put("wifi", "Wi-Fi attack techniques (WPA/WPS, handshake capture, deauth)");
        CONCEPT.put("defense", "defensive security and hardening");
        CONCEPT.put("learning", "general security topics");
    }

    // ---- clearly illegal / severe-harm content: NEVER forward ----
    private static final String[] BLOCKED = {
            "make a bomb","build a bomb","pipe bomb","plastic explosive","detonate a","c4 explosive",
            "tnt bomb","how to poison","ricin","anthrax","sarin","nerve agent","bioweapon",
            "biological weapon","make meth","meth recipe","methamphetamine","crystal meth",
            "carding method","carding tutorial","cvv cashout","cashout cvv","credit card fraud",
            "csam","child porn","minor porn","kill someone","murder someone","assassinate",
            "mass shooting","school shooting","make a weapon","3d print gun","ghost gun",
            "explosives tutorial","how to make a bomb","weaponize","make anthrax"
    };

    private static final String[] UNAUTHORIZED = {
            "someone else","someone's","neighbor","neighbour","friend's","friend","random",
            "stranger","victim","their network","their wifi","their router","other's","others",
            "third party","third-party","without permission","no permission","unauthorized",
            "illegal","steal","hack into","break into","target ip","target the","attack the",
            "company","bank","government","school network","public wifi","hotel wifi","cafe wifi",
            "airport wifi","office network","shop wifi","store network","gym wifi","cfgame"
    };
    private static final String[] OWN_SCOPE = {
            "my own","my lab","my device","my router","my network","my wifi","my server",
            "authorized","authorised","written permission","dvwa","webgoat","owasp","ctf",
            "homelab","home lab","lab ","pentest lab","my vm","virtual machine i own",
            "practice environment","test environment i control","my phone","my android",
            "my pc","my laptop","my website i own","my server i own"
    };
    // strong defensive / protective language -> question is about defense
    private static final String[] DEFENSE_BOOST = {
            "prevent","defend","defense","defensive","protect","mitigat","harden","secure",
            "securing","detection","detect","avoid","stop ","block","patch","fix vulnerab",
            "best practice","stay safe","how to secure","how to protect","safeguard","lock down"
    };
    // learning / curiosity verbs -> "how does X work" type questions
    private static final String[] LEARN_VERBS = {
            "explain","what is","what's","what are","how does","how do","how is","tutorial",
            "learn","concept","theory","guide","example of","difference between","overview of",
            "introduction to","why does","why is"
    };
    // explicit ATTACK-INTENT phrases. Deliberately phrased so they do NOT match
    // the noun forms that appear inside category words (e.g. "inject" must not
    // match "SQL injection", "attack" must not match "man-in-the-middle attack",
    // "phish" must not match the noun "phishing"). Only genuine "do the bad thing"
    // instructions count as attack intent.
    private static final String[] ATTACK_INTENT = {
            "hack into","hack the","hacking into","attack the","attack a","breach","exploit a",
            "exploit the","exploiting a","ddos","take over","compromise","exfiltrat","gain access to",
            "gain access","get into","break into","crack a","crack the","brute force","payload to",
            "trojan to","ransomware to","keylog","backdoor into","harvest credentials","steal credentials",
            "steal data","overload","disrupt the","intercept traffic","spoof a","phish for","carding",
            "weaponize","make a bomb","build a bomb","make anthrax","make meth"
    };

    private static final Pattern IP = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    public static Verdict classify(String prompt) {
        Verdict v = new Verdict();
        v.original = prompt == null ? "" : prompt;
        if (prompt == null) prompt = "";

        String p = prompt.toLowerCase(java.util.Locale.US);

        // 1. blocked content first (highest priority)
        boolean blocked = containsAny(p, BLOCKED);

        // 2. categories
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, String[]> e : CATEGORIES.entrySet()) {
            for (String kw : e.getValue()) {
                if (p.contains(kw)) { matched.add(e.getKey()); break; }
            }
        }
        v.tags = matched;

        // primary = first OFFENSIVE category if any
        String primary = "learning";
        for (String off : new String[]{"recon","sqli","xss","creds","phishing","malware",
                "dos","mitm","privesc","social","wifi"}) {
            if (matched.contains(off)) { primary = off; break; }
        }
        if (primary.equals("learning") && matched.contains("defense")) primary = "defense";
        v.category = primary;

        // 3. scope + intent signals
        boolean unauth = containsAny(p, UNAUTHORIZED);
        boolean own = containsAny(p, OWN_SCOPE);
        boolean hasTarget = IP.matcher(prompt).find();
        boolean defBoost = containsAny(p, DEFENSE_BOOST);
        boolean learnVerb = containsAny(p, LEARN_VERBS);
        boolean attackIntent = containsAny(p, ATTACK_INTENT);

        // Offensive = an offensive category matched OR there is explicit
        // attack intent (e.g. "hack into my neighbor's wifi").
        boolean offensive = (!primary.equals("defense") && !primary.equals("learning"))
                || attackIntent;

        // 4. decide
        if (blocked) {
            v.level = BLOCKED;
            v.blocked = true;
            v.rewritten = false;
            v.safePrompt = null;
            v.noteEn = "This request matches clearly illegal or severe-harm content and was "
                    + "BLOCKED. It was not forwarded to any AI. Such content is outside authorized "
                    + "security education (labs, CTFs, defensive research).";
            v.noteHi = "Ye request clearly illegal / severe-harm content se match kar rahi hai — "
                    + "isliye BLOCK kar diya gaya. Kisi AI ko forward nahi hua. Aisi cheez authorized "
                    + "security education (lab/CTF/defensive research) ke bahar hai.";
        } else if (offensive) {
            if (defBoost && !attackIntent && !unauth) {
                // "how to prevent / defend against X" -> safe, forward as-is
                v.level = SAFE;
            } else if (unauth && !own) {
                v.level = UNSAFE;
            } else if (own) {
                v.level = EDUCATIONAL;
            } else if (learnVerb && !hasTarget && !attackIntent) {
                // "explain how X works" -> educational, forward as-is
                v.level = EDUCATIONAL;
            } else {
                v.level = CAUTION;
            }
        } else {
            v.level = SAFE;
        }

        // 5. rewrite when needed
        if (v.level == UNSAFE || v.level == CAUTION) {
            v.rewritten = true;
            v.safePrompt = buildSafe(primary, v.level);
            if (v.level == UNSAFE) {
                v.noteEn = "Original prompt targeted a system outside your authorized scope. "
                        + "It was rewritten into an educational + defensive version. Only test "
                        + "systems you own or have written authorization for.";
                v.noteHi = "Original prompt kisi unauthorized/third-party target ke liye tha — usko "
                        + "safe educational form me badal diya gaya. Sirf apne ya authorized lab pe hi test karein.";
            } else {
                v.noteEn = "Prompt was ambiguous about scope or intent. Rewritten to a safe "
                        + "educational + defensive version. Confirm authorized scope before any "
                        + "hands-on practice.";
                v.noteHi = "Prompt ka scope ya intent clear nahi tha — use safe educational form me "
                        + "convert kar diya. Hath se practice se pehle apna authorized scope confirm karein.";
            }
        } else if (v.level == EDUCATIONAL) {
            v.noteEn = "Authorized-scope practice detected. Forwarded as-is.";
            v.noteHi = "Authorized scope (apna lab/CTF) detect hua — waise hi aage bheja.";
        } else if (v.level != BLOCKED) {
            v.noteEn = "General / defensive question. Forwarded as-is.";
            v.noteHi = "General ya defensive sawal — waise hi aage bheja.";
        }
        return v;
    }

    private static String buildSafe(String category, int level) {
        String concept = CONCEPT.getOrDefault(category, "the requested security topic");
        StringBuilder b = new StringBuilder();
        b.append("Explain the cybersecurity topic \"").append(concept)
         .append("\" for educational and defensive purposes only.\n\n")
         .append("Please cover:\n")
         .append("1. What the technique is and the underlying vulnerability or mechanism.\n")
         .append("2. How defenders detect and prevent it (logging, WAF, rate-limiting, ")
         .append("network segmentation, patching, user awareness).\n")
         .append("3. How a beginner can practice it legally and safely — for example OWASP ")
         .append("WebGoat, DVWA, PortSwigger Web Security Academy, a home lab you fully own, ")
         .append("or authorized CTF events.\n\n")
         .append("Do NOT provide operational step-by-step instructions to attack systems you ")
         .append("do not own or lack written authorization to test. The goal is to understand ")
         .append("the threat so it can be defended against.\n");
        if (level == UNSAFE) {
            b.append("\n(Note: any specific third-party target or IP in the original request was ")
             .append("removed and replaced with authorized practice scope.)\n");
        }
        return b.toString();
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static String jq(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append("\"");
        return b.toString();
    }
}
