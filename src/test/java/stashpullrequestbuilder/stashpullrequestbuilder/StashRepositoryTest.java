package stashpullrequestbuilder.stashpullrequestbuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.BooleanParameterDefinition;
import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashApiClient;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashApiClient.StashApiException;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestBuildTarget;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestComment;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValue;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValueRepository;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValueRepositoryBranch;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValueRepositoryRepository;

@RunWith(MockitoJUnitRunner.class)
public class StashRepositoryTest {

  @Rule public JenkinsRule jenkinsRule = new JenkinsRule();
  @Rule public MockitoRule rule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

  private StashRepository stashRepository;

  private StashCause cause;
  private FreeStyleProject project;
  private StashPollingAction pollLog;
  private StashPullRequestResponseValue pullRequest;
  private List<StashPullRequestResponseValue> pullRequestList;

  @Mock private StashBuildTrigger trigger;
  @Mock private StashApiClient stashApiClient;
  @Mock private ParametersDefinitionProperty parametersDefinitionProperty;

  @Before
  public void before() throws Exception {
    project = spy(jenkinsRule.createFreeStyleProject());
    pollLog = new StashPollingAction(project);
    stashRepository = new StashRepository(project, trigger, stashApiClient, pollLog);

    StashPullRequestResponseValueRepositoryBranch branch =
        new StashPullRequestResponseValueRepositoryBranch();
    branch.setName("feature/add-bloat");

    StashPullRequestResponseValueRepositoryRepository repoRepo =
        new StashPullRequestResponseValueRepositoryRepository();

    StashPullRequestResponseValueRepository repository =
        new StashPullRequestResponseValueRepository();
    repository.setBranch(branch);
    repository.setRepository(repoRepo);

    pullRequest = new StashPullRequestResponseValue();
    pullRequest.setFromRef(repository);
    pullRequest.setToRef(repository);
    pullRequest.setState("OPEN");
    pullRequest.setTitle("Add some bloat");

    pullRequestList = Collections.singletonList(pullRequest);
  }

  private StashCause makeCause(Map<String, String> additionalParameters) {
    return new StashCause(
        "StashHost",
        "SourceBranch",
        "TargetBranch",
        "SourceRepositoryOwner",
        "SourceRepositoryName",
        "PullRequestId",
        "DestinationRepositoryOwner",
        "DestinationRepositoryName",
        "PullRequestTitle",
        "SourceCommitHash",
        "DestinationCommitHash",
        "BuildStartCommentId",
        null,
        "PullRequestVersion",
        additionalParameters);
  }

  private void jobSetup(ParameterDefinition... parameterDefinitions) {
    when(project.getProperty(ParametersDefinitionProperty.class))
        .thenReturn(parametersDefinitionProperty);
    when(parametersDefinitionProperty.getParameterDefinitions())
        .thenReturn(Arrays.asList(parameterDefinitions));
  }

  private List<ParameterValue> captureBuildParameters() {
    Queue.Item item = stashRepository.startJob(cause);
    assertThat(item, is(notNullValue()));

    ParametersAction parametersAction = item.getAction(ParametersAction.class);
    assertThat(parametersAction, is(notNullValue()));

    return parametersAction.getAllParameters();
  }

  @Test
  public void getTargetPullRequests_returns_empty_list_for_no_pull_requests() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(Collections.emptyList());

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_accepts_open_pull_requests() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getBuildTargets_skips_merged_pull_requests() throws Exception {
    pullRequest.setState("MERGED");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_skips_null_state_pull_requests() throws Exception {
    pullRequest.setState(null);

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_accepts_matching_branches() throws Exception {
    when(trigger.getTargetBranchesToBuild()).thenReturn("release/.*,feature/.*,testing/.*");

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_accepts_matching_branches_with_padding() throws Exception {
    when(trigger.getTargetBranchesToBuild())
        .thenReturn("\trelease/.*, \n\tfeature/.* \r\n, testing/.*\r");

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_skips_mismatching_branches() throws Exception {
    when(trigger.getTargetBranchesToBuild()).thenReturn("release/.*,testing/.*");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_accepts_any_branch_if_Branches_to_Build_is_empty() throws Exception {
    when(trigger.getTargetBranchesToBuild()).thenReturn("");

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_accepts_any_branch_if_Branches_to_Build_is_null() throws Exception {
    when(trigger.getTargetBranchesToBuild()).thenReturn(null);

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_skips_on_Skip_Phrase_in_title() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");

    pullRequest.setTitle("NO TEST");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_skips_on_Skip_Phrase_in_comments() throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");
    when(project.getFullName()).thenReturn("Pull Request Builder Project");

    List<StashPullRequestComment> comments =
        Collections.singletonList(new StashPullRequestComment("NO TEST"));
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_notBuildOnlyOnComment_prioritizes_latest_comments() throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");
    when(project.getFullName()).thenReturn("Pull Request Builder Project");

    List<StashPullRequestComment> comments =
        Arrays.asList(
            new StashPullRequestComment(1, "NO TEST"), new StashPullRequestComment(2, "DO TEST"));
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_ignores_Skip_Phrase_case() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn("disable ANY Testing");

    pullRequest.setTitle("Disable any testing");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_matches_Skip_Phrase_as_substring() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");

    pullRequest.setTitle("This will get no testing whatsoever");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_supports_multiple_Skip_Phrases_and_padding() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn("\tuntestable , \n NO TEST\t, \r\ndon't worry!");

    pullRequest.setTitle("This will get no testing whatsoever");

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void getBuildTargets_builds_if_Skip_Phrase_is_empty() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn("");

    pullRequest.setTitle("NO TEST");

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_builds_if_Skip_Phrase_is_null() throws Exception {
    when(trigger.getCiSkipPhrases()).thenReturn(null);

    pullRequest.setTitle("NO TEST");

    assertThat(
        stashRepository.getBuildTargets(pullRequest),
        allOf(hasSize(1), contains(hasProperty("pullRequest", equalTo(pullRequest)))));
  }

  @Test
  public void getBuildTargets_skips_if_getPullRequestMergeStatus_throws() throws Exception {
    when(trigger.getCheckProbeMergeStatus()).thenReturn(true);
    when(stashApiClient.getPullRequestMergeStatus(pullRequest.getId()))
        .thenThrow(new StashApiException("Unknown Status"));

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }

  @Test
  public void
      getBuildTargets_notOnlyBuildOnComment_resets_parameters_if_none_specified_in_build_command()
          throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    StashPullRequestComment comment1 =
        new StashPullRequestComment(1, "DO TEST\np:key1=value1\np:key2=value2");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(
        buildTargets,
        allOf(hasSize(1), contains(hasProperty("additionalParameters", aMapWithSize(0)))));
  }

  @Test
  public void
      getBuildTargets_notOnlyBuildOnComment_ignores_parameter_definitions_before_build_command()
          throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key1=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key2=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(
        buildTargets,
        allOf(
            hasSize(1),
            contains(
                hasProperty(
                    "additionalParameters",
                    allOf(is(aMapWithSize(1)), hasEntry("key2", "value2"))))));
  }

  @Test
  public void getBuildTargets_notOnlyBuildOnComment_uses_newest_parameter_definition()
      throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(
        buildTargets,
        allOf(
            hasSize(1),
            contains(
                hasProperty(
                    "additionalParameters",
                    allOf(is(aMapWithSize(1)), hasEntry("key", "value2"))))));
  }

  @Test
  public void
      getBuildTargets_notOnlyBuildOnComment_uses_newest_parameter_definition_regardless_of_list_order()
          throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(false);
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment2, comment1);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    stashRepository.getTargetPullRequests();
    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(
        buildTargets,
        allOf(
            hasSize(1),
            contains(
                hasProperty(
                    "additionalParameters",
                    allOf(is(aMapWithSize(1)), hasEntry("key", "value2"))))));
  }

  @Test
  public void getBuildTargets_onlyBuildOnComment_multiple_comments_generate_multiple_targets()
      throws Exception {
    StashPullRequestComment comment1 =
        new StashPullRequestComment(1, "DO TEST\np:key1=value1\np:key2=value2");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");
    when(trigger.getOnlyBuildOnComment()).thenReturn(true);

    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(buildTargets, hasSize(2));
    assertThat(
        buildTargets,
        containsInAnyOrder(
            allOf(
                hasProperty("additionalParameters", aMapWithSize(2)),
                hasProperty(
                    "additionalParameters",
                    allOf(hasEntry("key1", "value1"), hasEntry("key2", "value2")))),
            hasProperty("additionalParameters", anEmptyMap())));
  }

  @Test
  public void getBuildTargets_onlyBuildOnComment_skip_if_buildStarted_or_buildFinished_message()
      throws Exception {
    when(trigger.getOnlyBuildOnComment()).thenReturn(true);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");
    when(project.getFullName()).thenReturn("MyProject");
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key1=value1");
    comment1.setReplies(
        Arrays.asList(
            new StashPullRequestComment("[*BuildStarted* **MyProject**] DEADBEEF into 1BADFACE")));
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key2=value2");
    comment2.setReplies(
        Arrays.asList(
            new StashPullRequestComment("[*BuildFinished* **MyProject**] DEADBEEF into 1BADFACE")));
    StashPullRequestComment comment3 = new StashPullRequestComment(2, "DO TEST\np:key3=value3");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2, comment3);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);

    Collection<StashPullRequestBuildTarget> buildTargets =
        stashRepository.getBuildTargets(pullRequest);

    assertThat(
        buildTargets,
        allOf(
            hasSize(1), contains(hasProperty("additionalParameters", hasEntry("key3", "value3")))));
  }

  @Test
  public void addFutureBuildTasks_removes_old_BuildFinished_comments_if_enabled() throws Exception {
    when(trigger.getDeletePreviousBuildFinishComments()).thenReturn(true);
    when(trigger.getStashHost()).thenReturn("StashHost");
    when(project.getFullName()).thenReturn("MyProject");
    List<StashPullRequestComment> comments =
        Arrays.asList(
            new StashPullRequestComment(2, "User comment"),
            new StashPullRequestComment(
                1, "[*BuildFinished* **MyProject**] DEADBEEF into 1BADFACE"));
    StashPullRequestComment response = new StashPullRequestComment(3, null);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    pullRequest.setId("123");
    stashRepository.addFutureBuildTask(new StashPullRequestBuildTarget(pullRequest));

    verify(stashApiClient, times(1)).deletePullRequestComment(eq("123"), eq("1"));
    verify(stashApiClient, times(0)).deletePullRequestComment(eq("123"), eq("2"));
  }

  @Test
  public void addFutureBuildTasks_schedules_build_if_deletePullRequestComment_throws()
      throws Exception {
    when(trigger.getDeletePreviousBuildFinishComments()).thenReturn(true);
    when(trigger.getStashHost()).thenReturn("http://localhost/");
    when(project.getFullName()).thenReturn("MyProject");
    doThrow(new StashApiException("Cannot Delete"))
        .when(stashApiClient)
        .deletePullRequestComment(any(), any());
    List<StashPullRequestComment> comments =
        Collections.singletonList(
            new StashPullRequestComment(1, "[*BuildFinished* **MyProject**] DEF2 into DEF1"));
    StashPullRequestComment response = new StashPullRequestComment(2, null);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    stashRepository.addFutureBuildTask(new StashPullRequestBuildTarget(pullRequest));

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(arrayWithSize(1)));
  }

  @Test
  public void addFutureBuildTasks_doesnt_schedule_build_if_postPullRequestComment_throws()
      throws Exception {
    when(stashApiClient.postPullRequestComment(any(), any()))
        .thenThrow(new StashApiException("Cannot Post"));

    stashRepository.addFutureBuildTask(new StashPullRequestBuildTarget(pullRequest));

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(emptyArray()));
  }

  @Test
  public void pollRepository_logs_time_and_stats() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(Collections.emptyList());

    stashRepository.pollRepository();

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(emptyArray()));
    String[] logLines = pollLog.toString().split("\\r?\\n|\\r");
    assertThat(logLines.length, is(equalTo(4)));
    assertThat(logLines[0], containsString(": poll started"));
    assertThat(logLines[1], is(equalTo("Number of open pull requests: 0")));
    assertThat(logLines[2], is(equalTo("Number of build targets to be built: 0")));
    assertThat(logLines[3], containsString(": poll completed in "));
  }

  @Test
  public void pollRepository_schedules_build_for_open_pull_request() throws Exception {
    when(trigger.getStashHost()).thenReturn("StashHost");
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(stashApiClient.getPullRequestComments(any(), any(), any()))
        .thenReturn(Collections.emptyList());
    StashPullRequestComment response = new StashPullRequestComment(1, null);
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    stashRepository.pollRepository();

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(arrayWithSize(1)));
  }

  @Test
  public void startJob_passes_parameter_with_default_value() {
    cause = makeCause(null);
    jobSetup(new StringParameterDefinition("param1", "param1_default"));

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, contains(new StringParameterValue("param1", "param1_default")));
  }

  @Test
  public void startJob_passes_parameter_with_value_from_StashCause() {
    Map<String, String> prParameters = new TreeMap<>();
    prParameters.put("param1", "param1_value");
    cause = makeCause(prParameters);

    ParameterDefinition parameterDefinition =
        new StringParameterDefinition("param1", "param1_default");
    jobSetup(parameterDefinition);

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, contains(new StringParameterValue("param1", "param1_value")));
  }

  @Test
  public void startJob_ignores_parameter_with_mismatching_name() {
    Map<String, String> prParameters = new TreeMap<>();
    prParameters.put("param2", "param2_value");
    cause = makeCause(prParameters);

    ParameterDefinition parameterDefinition =
        new StringParameterDefinition("param1", "param1_default");
    jobSetup(parameterDefinition);

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, contains(new StringParameterValue("param1", "param1_default")));
  }

  @Test
  public void startJob_replaces_value_of_nonstring_parameter() {
    Map<String, String> prParameters = new TreeMap<>();
    prParameters.put("param1", "param1_value");
    cause = makeCause(prParameters);
    jobSetup(new BooleanParameterDefinition("param1", false, "parameter 1"));

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, contains(new StringParameterValue("param1", "param1_value")));
  }

  @Test
  public void startJob_skips_null_parameters() {
    cause = makeCause(null);
    jobSetup(new FileParameterDefinition("param1", "parameter 1"));

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, is(empty()));
  }

  @Test
  public void parameters_populated_from_StashCause() {
    cause = makeCause(null);
    jobSetup(new StringParameterDefinition("sourceBranch", "DefaultBranch"));

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(
        parameters, contains(new StringParameterValue("sourceBranch", cause.getSourceBranch())));
  }

  @Test
  public void parameters_from_pull_request_comments_overridden_by_StashCause() {
    Map<String, String> prParameters = new TreeMap<>();
    prParameters.put("sourceBranch", "BranchFromPrComment");
    cause = makeCause(prParameters);
    jobSetup(new StringParameterDefinition("sourceBranch", "DefaultBranch"));

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(
        parameters, contains(new StringParameterValue("sourceBranch", cause.getSourceBranch())));
  }

  @Test
  public void getTargetPullRequests_returns_empty_if_getPullRequests_throws() throws Exception {
    when(stashApiClient.getPullRequests()).thenThrow(new StashApiException("cannot read PR list"));

    assertThat(stashRepository.getTargetPullRequests(), is(empty()));
  }

  @Test
  public void getBuildTargets_skips_pull_request_if_getPullRequestComments_throws()
      throws Exception {
    when(stashApiClient.getPullRequestComments(any(), any(), any()))
        .thenThrow(new StashApiException("cannot read PR comments"));

    assertThat(stashRepository.getBuildTargets(pullRequest), empty());
  }
}
