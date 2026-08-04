package io.github.joyen09.exchangecore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The boundary enforcement owed by ADR-0002, and the rules ADR-0003 and ADR-0005 extend it with.
 *
 * <p>Package boundaries are advisory until something mechanical checks them; a comment saying
 * "the ledger must not know about orders" is worth exactly as much as the attention of whoever reads
 * it next. These rules are the difference between a documented intention and an enforced one.
 */
@AnalyzeClasses(
        packages = "io.github.joyen09.exchangecore",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /**
     * ADR-0005 §7. A second write path that forgot to take the account lock would reintroduce the
     * overdraft race silently, so there is exactly one class allowed to speak SQL to the ledger.
     */
    @ArchTest
    static final ArchRule onlyTheLedgerRepositoryTalksToTheDatabase = noClasses()
            .that()
            .resideInAPackage("..ledger..")
            .and()
            .haveSimpleNameNotEndingWith("Repository")
            .and()
            .haveSimpleNameNotEndingWith("QueryService")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .because("every ledger write must go through the one path that locks before it reads (ADR-0005)");

    /**
     * ADR-0005 §6. The read path returns a balance that may already be stale; it is display-only.
     * Keeping it unreachable from the write package means the misuse cannot be written there.
     */
    @ArchTest
    static final ArchRule theWritePathCannotSeeTheReadPath = noClasses()
            .that()
            .resideInAPackage("io.github.joyen09.exchangecore.ledger")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.joyen09.exchangecore.ledger.query")
            .because("a balance read without a lock must never become an input to a write decision (ADR-0005)");

    /**
     * SPEC §3. The ledger records movement of value between accounts; it does not know what an order
     * is. This is what keeps it reusable for the optional wallet work in Phase 6.
     */
    @ArchTest
    static final ArchRule theLedgerKnowsNothingOfTheRestOfTheSystem = noClasses()
            .that()
            .resideInAPackage("..ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..order..", "..exchange..", "..recon..", "..risk..")
            .because("the ledger moves value between accounts and nothing else (SPEC §3)");

    /**
     * ADR-0003 extension. Configuration-level coverage stops something being *configured* to reach
     * the wrong venue; this stops something being *written* to bypass configuration entirely.
     */
    @ArchTest
    static final ArchRule onlyTheExchangeModuleSpeaksToTheNetwork = noClasses()
            .that()
            .resideOutsideOfPackage("..exchange..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.http.HttpClient")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.http.WebSocket")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestClient")
            .because("the endpoint allowlist only holds if every outbound call goes through the guarded module");

    /** SPEC §5.1. A float or double anywhere near money is a bug, so none exist anywhere. */
    @ArchTest
    static final ArchRule moneyIsNeverAFloatingPointNumber = fields()
            .should()
            .notHaveRawType(double.class)
            .andShould()
            .notHaveRawType(float.class)
            .andShould()
            .notHaveRawType(Double.class)
            .andShould()
            .notHaveRawType(Float.class)
            .because("amounts are BigDecimal end to end (SPEC §5.1)");
}
