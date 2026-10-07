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
            .resideInAnyPackage(
                    "..order..", "..exchange..", "..recon..", "..risk..", "..api..", "..outbox..", "..idempotency..")
            .because("the ledger moves value between accounts and nothing else (SPEC §3)");

    /**
     * ADR-0005 §7, generalised to Phase 2. Three more packages now own tables, and the same argument
     * applies to each: {@code OrderService} inserting its own SQL would be a second write path that nobody
     * would think to give the row lock or the append-only event to. The suffix is the whole rule — if a
     * class needs a {@code JdbcTemplate}, it has to be named as the thing that owns persistence.
     */
    @ArchTest
    static final ArchRule onlyRepositoriesTalkToTheDatabase = noClasses()
            .that()
            .resideInAnyPackage("..order..", "..outbox..", "..idempotency..", "..api..")
            .and()
            .haveSimpleNameNotEndingWith("Repository")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .because("persistence lives behind one named class per table, so its invariants have one home");

    /**
     * The outbox is a delivery mechanism, not part of the order domain. It moves rows that name an
     * aggregate type and an opaque payload; it has never needed to know that one of those aggregates is an
     * order, and this is what stops the next feature from teaching it. The reusability is the point:
     * Phase 4's reconciliation events publish through the same table without touching this package.
     */
    @ArchTest
    static final ArchRule theOutboxDoesNotKnowWhatItIsDelivering = noClasses()
            .that()
            .resideInAPackage("io.github.joyen09.exchangecore.outbox")
            .and()
            // The verification consumer is the exception, and deliberately a named one: its whole job is to
            // read the order events back off the broker, so it is the one class here that must know the shape
            // of the envelope.
            .haveSimpleNameNotStartingWith("OrderEventVerifier")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..order..", "..ledger..", "..api..")
            .because("the outbox carries an aggregate id and a payload; what they mean is not its concern");

    /**
     * The domain does not reach back up into the transport. An exception or a status code decided inside
     * {@code OrderService} would make the service unusable from anywhere but a controller — and Phase 3's
     * exchange adapter drives exactly these transitions with no HTTP request in sight.
     */
    @ArchTest
    static final ArchRule theDomainDoesNotDependOnTheWebLayer = noClasses()
            .that()
            .resideInAnyPackage("..order..", "..ledger..", "..outbox..", "..idempotency..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..api..")
            .orShould()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            .because("the same transitions are driven by the exchange adapter in Phase 3, with no request");

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
