package ma.cdg.claims.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Guards the contract between the code and the deployed model.
 *
 * <p>{@link WorkflowStep} hard-codes the BPMN element ids and candidate groups. If somebody
 * renames a task in the Modeler without updating the enum, the application would silently
 * stop recognising its own tasks — this test fails first instead.
 */
class BpmnModelConsistencyTest {

    private static final String RESOURCE = "bpmn/reclamation-client-cdg.bpmn";

    @Test
    @DisplayName("every workflow step exists in the model as a user task")
    void everyStepExistsInTheModel() throws Exception {
        List<Element> userTasks = userTasks();
        List<String> ids = userTasks.stream().map(task -> task.getAttribute("id")).toList();

        for (WorkflowStep step : WorkflowStep.values()) {
            assertThat(ids)
                    .as("BPMN user task for step %s", step)
                    .contains(step.getElementId());
        }
        assertThat(ids).hasSameSizeAs(WorkflowStep.values());
    }

    @Test
    @DisplayName("each user task is assigned to the candidate group its role expects")
    void candidateGroupsMatchTheRoles() throws Exception {
        for (Element task : userTasks()) {
            String id = task.getAttribute("id");
            WorkflowStep step = WorkflowStep.fromElementId(id).orElse(null);
            assertThat(step).as("unknown user task '%s' in the model", id).isNotNull();

            String candidateGroups = firstAttribute(task, "assignmentDefinition", "candidateGroups");
            assertThat(candidateGroups)
                    .as("candidate group of %s", id)
                    .isEqualTo(step.getCandidateGroup());
        }
    }

    @Test
    @DisplayName("each user task is a Camunda user task, so the API can list it")
    void tasksAreCamundaUserTasks() throws Exception {
        for (Element task : userTasks()) {
            assertThat(task.getElementsByTagNameNS("*", "userTask").getLength())
                    .as("zeebe:userTask marker on %s", task.getAttribute("id"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the process id is the one the application starts instances of")
    void processIdMatches() throws Exception {
        NodeList processes = model().getElementsByTagNameNS("*", "process");
        assertThat(processes.getLength()).isEqualTo(1);
        assertThat(((Element) processes.item(0)).getAttribute("id"))
                .isEqualTo("reclamation-client-cdg");
    }

    @Test
    @DisplayName("the gateway conditions read the variables the application writes")
    void gatewayConditionsUseTheExpectedVariables() throws Exception {
        String xml = new String(open().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(xml).contains("qualificationDecision = \"VALID\"");
        assertThat(xml).contains("foCanAnswer = true");
        assertThat(xml).contains("moCanAnswer = true");
        assertThat(xml).contains("validationDecision = \"APPROVED\"");
    }

    // ----------------------------------------------------------------- helpers

    private static InputStream open() {
        InputStream stream = BpmnModelConsistencyTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE);
        assertThat(stream).as("%s on the classpath", RESOURCE).isNotNull();
        return stream;
    }

    private static Document model() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(open());
    }

    private static List<Element> userTasks() throws Exception {
        NodeList nodes = model().getElementsByTagNameNS("*", "userTask");
        List<Element> tasks = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            // The zeebe:userTask marker is nested inside the bpmn:userTask; keep only the outer one.
            if (element.hasAttribute("id")) {
                tasks.add(element);
            }
        }
        return tasks;
    }

    private static String firstAttribute(Element parent, String localName, String attribute) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && element.hasAttribute(attribute)) {
                return element.getAttribute(attribute);
            }
        }
        return null;
    }
}
