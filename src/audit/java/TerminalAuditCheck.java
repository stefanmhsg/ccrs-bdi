import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import jason.JasonException;
import jason.asSemantics.Agent;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.Term;
import jason.asSyntax.VarTerm;
import jason.asSyntax.parser.as2j;
import jia.epoch_millis;

/** Offline syntax and internal-action checks; no reasoning cycle or environment. */
public final class TerminalAuditCheck {
    public static void main(String[] args) throws Exception {
        for (String file : new String[]{"dfs_baseline.asl", "dfs_ccrs.asl"}) {
            String source = Files.readString(Path.of("src/agt", file));
            // Template resolution belongs to JaCaMo setup; check application syntax
            // without loading framework directives or executing initial goals.
            source = source.replaceAll("(?m)^\\{ include\\([^\\r\\n]*\\) \\}\\s*$", "");
            Agent syntax = new Agent();
            syntax.setConsiderToAddMIForThisAgent(false);
            syntax.initAg();
            as2j parser = new as2j(new StringReader(source));
            parser.setASLSource(file);
            if (!parser.agent(syntax)) throw new AssertionError("Parse failed: " + file);
            if (syntax.getPL().size() == 0) throw new AssertionError("No plans parsed: " + file);
            System.out.println("Parsed application plans: " + file);
        }
        epoch_millis action = new epoch_millis();
        Unifier binding = new Unifier();
        long before = System.currentTimeMillis();
        if (!Boolean.TRUE.equals(action.execute(null, binding, new Term[]{new VarTerm("Time")}))) {
            throw new AssertionError("Timestamp did not bind");
        }
        double timestamp = ((NumberTerm) binding.get("Time")).solve();
        if (timestamp < before || timestamp > System.currentTimeMillis()) {
            throw new AssertionError("Timestamp is not the current UTC epoch observation");
        }
        try {
            action.execute(null, new Unifier(), new Term[0]);
            throw new AssertionError("Missing output argument was accepted");
        } catch (JasonException expected) {
            // Invalid arity must remain an explicit AgentSpeak error.
        }
        System.out.println("UTC epoch binding and arity checks passed; no agent was run.");
    }
}
