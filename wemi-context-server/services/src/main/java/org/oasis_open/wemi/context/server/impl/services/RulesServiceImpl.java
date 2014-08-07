package org.oasis_open.wemi.context.server.impl.services;

import org.oasis_open.wemi.context.server.api.Event;
import org.oasis_open.wemi.context.server.api.conditions.Condition;
import org.oasis_open.wemi.context.server.api.rules.Rule;
import org.oasis_open.wemi.context.server.api.services.DefinitionsService;
import org.oasis_open.wemi.context.server.api.services.EventListenerService;
import org.oasis_open.wemi.context.server.api.consequences.Consequence;
import org.oasis_open.wemi.context.server.api.services.RulesService;
import org.oasis_open.wemi.context.server.impl.consequences.ConsequenceExecutorDispatcher;
import org.oasis_open.wemi.context.server.persistence.spi.PersistenceService;
import org.ops4j.pax.cdi.api.OsgiService;
import org.ops4j.pax.cdi.api.OsgiServiceProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.json.*;
import java.net.URL;
import java.util.*;

@Singleton
@OsgiServiceProvider
public class RulesServiceImpl implements RulesService, EventListenerService, BundleListener {

    private static final Logger logger = LoggerFactory.getLogger(RulesServiceImpl.class.getName());

    @Inject
    private BundleContext bundleContext;

    @Inject
    @OsgiService
    private PersistenceService persistenceService;

    @Inject
    private DefinitionsService definitionsService;

    @Inject
    private ConsequenceExecutorDispatcher consequenceExecutorDispatcher;

    Map<String, Rule> rules = new LinkedHashMap<String, Rule>();

    @PostConstruct
    public void postConstruct() {
        logger.debug("postConstruct {" + bundleContext.getBundle() + "}");

        loadPredefinedRules(bundleContext);
        bundleContext.addBundleListener(this);
    }

    @PreDestroy
    public void preDestroy() {
        bundleContext.removeBundleListener(this);
    }

    private void loadPredefinedRules(BundleContext bundleContext) {
        Enumeration<URL> predefinedRuleEntries = bundleContext.getBundle().findEntries("META-INF/wemi/rules", "*.json", true);
        if (predefinedRuleEntries == null) {
            return;
        }
        while (predefinedRuleEntries.hasMoreElements()) {
            URL predefinedSegmentURL = predefinedRuleEntries.nextElement();
            logger.debug("Found predefined segment at " + predefinedSegmentURL + ", loading... ");

            JsonReader reader = null;
            try {
                reader = Json.createReader(predefinedSegmentURL.openStream());
                JsonStructure jsonst = reader.read();

                // dumpJSON(jsonst, null, "");
                JsonObject ruleObject = (JsonObject) jsonst;

                String ruleID = ruleObject.getString("id");
                Rule rule = new Rule();

                Condition condition = ParserHelper.parseCondition(definitionsService, ruleObject.getJsonObject("condition"));
                rule.setRootCondition(condition);

                JsonArray array = ruleObject.getJsonArray("consequences");
                Set<Consequence> consequences = new HashSet<Consequence>();
                for (JsonValue value : array) {
                    consequences.add(ParserHelper.parseConsequence(definitionsService, (JsonObject) value));
                }
                rule.setConsequences(consequences);
                persistenceService.saveQuery(ruleID, rule.getRootCondition());

                rules.put(ruleID, rule);
            } catch (Exception e) {
                logger.error("Error while loading segment definition " + predefinedSegmentURL, e);
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        }
    }

    public Set<Rule> getMatchingRules(Event event) {
        Set<Rule> matchedRules = new LinkedHashSet<Rule>();

        List<String> matchingQueries = persistenceService.getMatchingSavedQueries(event);

        if (matchingQueries.size() > 0) {
            for (String matchingQuery : matchingQueries) {
                if (rules.containsKey(matchingQuery)) {
                    matchedRules.add(rules.get(matchingQuery));
                }
            }
        }

        return matchedRules;
     }


    public boolean canHandle(Event event) {
        return true;
    }

    public boolean onEvent(Event event) {
        Set<Rule> rules = getMatchingRules(event);

        boolean changed = false;
        for (Rule rule: rules) {
            for (Consequence consequence : rule.getConsequences()) {
                changed |= consequenceExecutorDispatcher.execute(consequence, event.getUser(), event);
            }
        }
        return changed;
    }

    public void bundleChanged(BundleEvent event) {
        switch (event.getType()) {
            case BundleEvent.STARTED:
                loadPredefinedRules(event.getBundle().getBundleContext());
                break;
            case BundleEvent.STOPPING:
                // @todo remove bundle-defined resources (is it possible ?)
                break;
        }
    }
}
