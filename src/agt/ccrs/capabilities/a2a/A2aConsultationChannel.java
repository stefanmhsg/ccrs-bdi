package ccrs.capabilities.a2a;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import ccrs.core.contingency.strategies.social.ConsultationStrategy;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.client.transport.rest.RestTransport;
import io.a2a.client.transport.rest.RestTransportConfig;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TextPart;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

/**
 * Consultation channel backed by the A2A Java SDK.
 *
 * The channel discovers an agent card URI from the consultation context,
 * resolves the card, invokes the first available skill, and returns the
 * resulting text payload, typically from an RDF/Turtle artifact.
 */
public class A2aConsultationChannel implements ConsultationStrategy.ConsultationChannel {

    private static final Logger logger = Logger.getLogger(A2aConsultationChannel.class.getName());
    private static final String AGENT_URI_KEY = "agentUri";
    private static final String AGENT_CARD_URI_KEY = "agentCardUri";
    private static final String CONSULTATION_TARGETS_KEY = "consultationTargets";
    private static final String A2A_AGENT_CARD = "https://example.org/a2a#agentCard";

    private final A2aConfig config;
    private final A2AHttpClient httpClient;

    private final Map<String, AgentCard> cachedCards = new HashMap<>();
    private final Map<String, String> cachedAgentCardUris = new HashMap<>();

    public A2aConsultationChannel(A2aConfig config) {
        this(config, new JdkA2AHttpClient());
    }

    A2aConsultationChannel(A2aConfig config, A2AHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return config != null
            && httpClient != null;
    }

    @Override
    public ConsultationStrategy.ConsultationResponse query(String question, Map<String, Object> context)
        throws Exception {

        if (!isAvailable()) {
            throw new IllegalStateException("A2A consultation channel is not properly configured");
        }

        Map<String, Object> selectedTarget = resolveSelectedTarget(context)
            .orElseThrow(() -> new IllegalStateException("No A2A consultation target available in consultation context"));
        String agentUri = asString(selectedTarget.get(AGENT_URI_KEY));
        logger.info("[A2A] Selected consultation target: " + selectedTarget);
        String agentCardUri = resolveAgentCardUri(selectedTarget)
            .orElseThrow(() -> new IllegalStateException("Could not resolve an A2A agent card from target: " + agentUri));
        logger.info("[A2A] Resolved agent card URI for " + agentUri + ": " + agentCardUri);

        AgentCard card = resolveAgentCard(agentCardUri);
        String skillId = resolveSkill(card);
        logger.info("[A2A] Using skill '" + skillId + "' from card '" + card.name() + "'");
        Message request = buildRequest(skillId, agentCardUri);

        AtomicReference<String> latestResponseText = new AtomicReference<>();
        AtomicReference<Task> latestTask = new AtomicReference<>();
        AtomicReference<Message> latestMessage = new AtomicReference<>();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        BiConsumer<ClientEvent, AgentCard> consumer = (event, ignoredCard) -> {
            if (config.isLogEvents()) {
                logger.info("[A2A] Received event: " + event.getClass().getSimpleName());
            }

            if (event instanceof TaskEvent taskEvent) {
                Task task = taskEvent.getTask();
                latestTask.set(task);
                String text = extractText(task);
                if (text != null && !text.isBlank()) {
                    latestResponseText.set(text);
                }
            } else if (event instanceof MessageEvent messageEvent) {
                Message message = messageEvent.getMessage();
                latestMessage.set(message);
                String text = extractText(message);
                if (text != null && !text.isBlank()) {
                    latestResponseText.set(text);
                }
            }
        };

        ClientCallContext callContext = new ClientCallContext(Map.of(), Map.of());
        RestTransportConfig transportConfig = new RestTransportConfig(httpClient);

        Client client = null;
        try {
            client = Client.builder(card)
                .withTransport(RestTransport.class, transportConfig)
                .build();
            client.sendMessage(request, List.of(consumer), streamError::set, callContext);
        } finally {
            if (client != null) {
                client.close();
            }
        }

        if (streamError.get() != null) {
            throw new Exception("A2A stream error: " + streamError.get().getMessage(), streamError.get());
        }

        String responseText = latestResponseText.get();
        if (responseText == null || responseText.isBlank()) {
            return ConsultationStrategy.ConsultationResponse.failure(
                "A2A consultation completed without a text response"
            );
        }
        logger.info("[A2A] Received consultation payload (" + responseText.length() + " chars)");

        ConsultationStrategy.ConsultationResponse response =
            ConsultationStrategy.ConsultationResponse.success(
                "consult",
                agentCardUri,
                responseText
            );
        response.source = buildSource(card, skillId);
        response.confidence = 0.7;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestedSkill", skillId);
        metadata.put("agentCardUri", agentCardUri);
        metadata.put("agentName", card.name());
        metadata.put("agentUrl", card.url());
        metadata.put("rawResponse", responseText);
        enrichArtifactMetadata(metadata, latestTask.get());

        if (latestTask.get() != null) {
            metadata.put("taskId", latestTask.get().getId());
            metadata.put("taskState", latestTask.get().getStatus().state().asString());
        }
        if (latestMessage.get() != null) {
            metadata.put("messageId", latestMessage.get().getMessageId());
        }

        response.metadata = metadata;
        return response;
    }

    @Override
    public String getChannelType() {
        return "a2a";
    }

    private Optional<Map<String, Object>> resolveSelectedTarget(Map<String, Object> context) {
        Object directAgentUri = context.get(AGENT_URI_KEY);
        if (directAgentUri instanceof String uri && !uri.isBlank()) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put(AGENT_URI_KEY, uri);
            Object directCardUri = context.get(AGENT_CARD_URI_KEY);
            if (directCardUri instanceof String cardUri && !cardUri.isBlank()) {
                target.put(AGENT_CARD_URI_KEY, cardUri);
            }
            return Optional.of(target);
        }

        Object targets = context.get(CONSULTATION_TARGETS_KEY);
        if (targets instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String agentUri = asString(map.get(AGENT_URI_KEY));
                    if (agentUri != null) {
                        Map<String, Object> target = new LinkedHashMap<>();
                        target.put(AGENT_URI_KEY, agentUri);
                        String agentCardUri = asString(map.get(AGENT_CARD_URI_KEY));
                        if (agentCardUri != null) {
                            target.put(AGENT_CARD_URI_KEY, agentCardUri);
                        }
                        logger.info("[A2A] Picked first consultation target from list: " + target);
                        return Optional.of(target);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveAgentCardUri(Map<String, Object> target) throws Exception {
        String direct = asString(target.get(AGENT_CARD_URI_KEY));
        if (direct != null) {
            logger.info("[A2A] Using agent card URI directly from context: " + direct);
            return Optional.of(direct);
        }

        String agentUri = asString(target.get(AGENT_URI_KEY));
        if (agentUri == null) {
            return Optional.empty();
        }

        synchronized (cachedAgentCardUris) {
            String cached = cachedAgentCardUris.get(agentUri);
            if (cached != null) {
                logger.info("[A2A] Using cached agent card URI for " + agentUri + ": " + cached);
                return Optional.of(cached);
            }
        }

        logger.info("[A2A] Dereferencing agent URI to discover agent card: " + agentUri);
        Optional<String> discovered = discoverAgentCardUri(agentUri);
        discovered.ifPresent(uri -> {
            synchronized (cachedAgentCardUris) {
                cachedAgentCardUris.put(agentUri, uri);
            }
            logger.info("[A2A] Cached discovered agent card URI for " + agentUri + ": " + uri);
        });
        return discovered;
    }

    private AgentCard resolveAgentCard(String agentCardUri) throws Exception {
        synchronized (cachedCards) {
            AgentCard cached = cachedCards.get(agentCardUri);
            if (cached != null) {
                logger.info("[A2A] Using cached agent card object for " + agentCardUri);
                return cached;
            }

            logger.info("[A2A] Resolving agent card object from URI: " + agentCardUri);
            AgentCard card = resolveCardFromUri(agentCardUri);
            cachedCards.put(agentCardUri, card);
            return card;
        }
    }

    private AgentCard resolveCardFromUri(String agentCardUri) throws Exception {
        URI uri = URI.create(agentCardUri);
        String path = uri.getPath();
        String baseUrl = new URI(uri.getScheme(), uri.getAuthority(), "/", null, null).toString();
        return new A2ACardResolver(httpClient, baseUrl, path).getAgentCard();
    }

    private Optional<String> discoverAgentCardUri(String agentUri) throws Exception {
        A2AHttpResponse response = httpClient.createGet()
            .url(agentUri)
            .addHeader("Accept", "text/turtle, application/ld+json, application/rdf+xml, application/n-triples")
            .get();

        if (!response.success()) {
            logger.warning("[A2A] Dereference of agent URI failed: " + agentUri + " status=" + response.status());
            return Optional.empty();
        }
        logger.info("[A2A] Dereference GET succeeded for " + agentUri + " status=" + response.status());

        String body = response.body();
        if (body == null || body.isBlank()) {
            logger.warning("[A2A] Dereference response body was empty for " + agentUri);
            return Optional.empty();
        }
        logger.info("[A2A] Dereference response body size for " + agentUri + ": " + body.length() + " chars");

        Model model = ModelFactory.createDefaultModel();
        try (java.io.StringReader reader = new java.io.StringReader(body)) {
            model.read(reader, agentUri, "TURTLE");
            logger.info("[A2A] Parsed dereference response as TURTLE for " + agentUri);
        } catch (Exception e) {
            logger.warning("[A2A] Could not parse agent resource as TURTLE for " + agentUri + ": " + e.getMessage());
            return Optional.empty();
        }

        Resource subject = model.createResource(agentUri);
        Property agentCardProperty = model.createProperty(A2A_AGENT_CARD);
        var statements = model.listStatements(subject, agentCardProperty, (RDFNode) null);
        try {
            if (!statements.hasNext()) {
                logger.warning("[A2A] No a2a:agentCard triple found after dereferencing " + agentUri);
                return Optional.empty();
            }

            RDFNode node = statements.next().getObject();
            if (node.isResource() && node.asResource().getURI() != null) {
                logger.info("[A2A] Discovered a2a:agentCard for " + agentUri + ": " + node.asResource().getURI());
                return Optional.of(node.asResource().getURI());
            }
            logger.warning("[A2A] a2a:agentCard object was not a URI resource for " + agentUri);
            return Optional.empty();
        } finally {
            statements.close();
        }
    }

    private String resolveSkill(AgentCard card) {
        if (card == null || card.skills() == null || card.skills().isEmpty()) {
            throw new IllegalStateException("A2A AgentCard does not expose any skills");
        }

        for (int i = 0; i < card.skills().size(); i++) {
            if (card.skills().get(i) != null && card.skills().get(i).id() != null
                && !card.skills().get(i).id().isBlank()) {
                return card.skills().get(i).id();
            }
        }

        throw new IllegalStateException("A2A AgentCard contains no usable skill identifiers");
    }

    private Message buildRequest(String skillId, String agentCardUri) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestedSkill", skillId);
        metadata.put("agentCardUri", agentCardUri);

        return new Message(
            Message.Role.USER,
            List.of(new TextPart(skillId)),
            "msg-" + UUID.randomUUID(),
            null,
            null,
            List.of(),
            metadata,
            List.of()
        );
    }

    private String buildSource(AgentCard card, String skillId) {
        String cardName = card != null && card.name() != null ? card.name() : "unknown-agent";
        return cardName + "/" + skillId;
    }

    private String extractText(Task task) {
        if (task == null) {
            return null;
        }

        String fromArtifacts = extractTextFromArtifacts(task.getArtifacts());
        if (fromArtifacts != null) {
            return fromArtifacts;
        }

        if (task.getStatus() != null && task.getStatus().message() != null) {
            String statusMessage = extractText(task.getStatus().message());
            if (statusMessage != null) {
                return statusMessage;
            }
        }

        return extractTextFromMessages(task.getHistory());
    }

    private String extractText(Message message) {
        if (message == null || message.getParts() == null) {
            return null;
        }

        List<String> texts = new ArrayList<>();
        for (Part<?> part : message.getParts()) {
            if (part instanceof TextPart textPart) {
                texts.add(textPart.getText());
            }
        }
        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    private String extractTextFromArtifacts(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return null;
        }

        for (int i = artifacts.size() - 1; i >= 0; i--) {
            Artifact artifact = artifacts.get(i);
            if (artifact == null || artifact.parts() == null) {
                continue;
            }

            List<String> texts = new ArrayList<>();
            for (Part<?> part : artifact.parts()) {
                if (part instanceof TextPart textPart) {
                    texts.add(textPart.getText());
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        return null;
    }

    private String extractTextFromMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            String text = extractText(messages.get(i));
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private void enrichArtifactMetadata(Map<String, Object> metadata, Task task) {
        if (task == null || task.getArtifacts() == null || task.getArtifacts().isEmpty()) {
            return;
        }

        Artifact artifact = task.getArtifacts().get(task.getArtifacts().size() - 1);
        if (artifact == null) {
            return;
        }

        metadata.put("artifactId", artifact.artifactId());
        metadata.put("artifactName", artifact.name());
        if (artifact.metadata() != null) {
            Object contentType = artifact.metadata().get("contentType");
            if (contentType != null) {
                metadata.put("artifactContentType", Objects.toString(contentType));
            }
        }
    }

    private String asString(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
