package stashpullrequestbuilder.stashpullrequestbuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
        "BuildCommandCommentId",
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
  public void getTargetPullRequests_skips_merged_pull_requests() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    pullRequest.setState("MERGED");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_skips_null_state_pull_requests() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    pullRequest.setState(null);

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_accepts_matching_branches() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getTargetBranchesToBuild()).thenReturn("release/.*,feature/.*,testing/.*");

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_accepts_matching_branches_with_padding() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getTargetBranchesToBuild())
        .thenReturn("\trelease/.*, \n\tfeature/.* \r\n, testing/.*\r");

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_skips_mismatching_branches() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getTargetBranchesToBuild()).thenReturn("release/.*,testing/.*");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_accepts_any_branch_if_Branches_to_Build_is_empty()
      throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getTargetBranchesToBuild()).thenReturn("");

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_accepts_any_branch_if_Branches_to_Build_is_null()
      throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getTargetBranchesToBuild()).thenReturn(null);

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_skips_on_Skip_Phrase_in_title() throws Exception {
    pullRequest.setTitle("NO TEST");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_skips_on_Skip_Phrase_in_comments() throws Exception {
    StashPullRequestComment comment = new StashPullRequestComment();
    comment.setText("NO TEST");
    List<StashPullRequestComment> comments = Collections.singletonList(comment);

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(project.getFullName()).thenReturn("Pull Request Builder Project");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_prioritizes_latest_comments() throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment();
    comment1.setCommentId(1);
    comment1.setText("NO TEST");

    StashPullRequestComment comment2 = new StashPullRequestComment();
    comment2.setCommentId(2);
    comment2.setText("DO TEST");

    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(project.getFullName()).thenReturn("Pull Request Builder Project");

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_ignores_Skip_Phrase_case() throws Exception {
    pullRequest.setTitle("Disable any testing");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("disable ANY Testing");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_matches_Skip_Phrase_as_substring() throws Exception {
    pullRequest.setTitle("This will get no testing whatsoever");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("NO TEST");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_supports_multiple_Skip_Phrases_and_padding() throws Exception {
    pullRequest.setTitle("This will get no testing whatsoever");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("\tuntestable , \n NO TEST\t, \r\ndon't worry!");

    assertThat(stashRepository.getTargetPullRequests(), empty());
  }

  @Test
  public void getTargetPullRequests_builds_if_Skip_Phrase_is_empty() throws Exception {
    pullRequest.setTitle("NO TEST");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn("");

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_builds_if_Skip_Phrase_is_null() throws Exception {
    pullRequest.setTitle("NO TEST");

    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiSkipPhrases()).thenReturn(null);

    assertThat(stashRepository.getTargetPullRequests(), contains(pullRequest));
  }

  @Test
  public void getTargetPullRequests_skips_if_getPullRequestMergeStatus_throws() throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCheckProbeMergeStatus()).thenReturn(true);
    when(stashApiClient.getPullRequestMergeStatus(pullRequest.getId()))
        .thenThrow(new StashApiException("Unknown Status"));

    assertThat(stashRepository.getTargetPullRequests(), is(empty()));
  }

  @Test
  public void getAdditionalParameters_resets_parameters_if_none_specified_in_build_command() throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key1=value1\np:key2=value2");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    stashRepository.getTargetPullRequests();
    Map<String, String> parameters = stashRepository.getAdditionalParameters(pullRequest);

    assertThat(parameters, is(aMapWithSize(0)));
  }

  @Test
  public void getAdditionalParameters_ignores_parameter_definitions_before_build_command() throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key1=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key2=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    stashRepository.getTargetPullRequests();
    Map<String, String> parameters = stashRepository.getAdditionalParameters(pullRequest);

    assertThat(parameters, is(aMapWithSize(1)));
    assertThat(parameters, hasEntry("key2", "value2"));
  }

  @Test
  public void getAdditionalParameters_uses_newest_parameter_definition() throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment1, comment2);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    stashRepository.getTargetPullRequests();
    Map<String, String> parameters = stashRepository.getAdditionalParameters(pullRequest);

    assertThat(parameters, is(aMapWithSize(1)));
    assertThat(parameters, hasEntry("key", "value2"));
  }

  @Test
  public void getAdditionalParameters_uses_newest_parameter_definition_regardless_of_list_order()
      throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment(1, "DO TEST\np:key=value1");
    StashPullRequestComment comment2 = new StashPullRequestComment(2, "DO TEST\np:key=value2");
    List<StashPullRequestComment> comments = Arrays.asList(comment2, comment1);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(trigger.getCiBuildPhrases()).thenReturn("DO TEST");

    stashRepository.getTargetPullRequests();
    Map<String, String> parameters = stashRepository.getAdditionalParameters(pullRequest);

    assertThat(parameters, is(aMapWithSize(1)));
    assertThat(parameters, hasEntry("key", "value2"));
  }

  @Test
  public void addFutureBuildTasks_removes_old_BuildFinished_comments_if_enabled() throws Exception {
    StashPullRequestComment comment1 = new StashPullRequestComment();
    comment1.setCommentId(1);
    comment1.setText("[*BuildFinished* **MyProject**] DEADBEEF into 1BADFACE");

    StashPullRequestComment comment2 = new StashPullRequestComment();
    comment2.setCommentId(2);
    comment2.setText("User comment");

    StashPullRequestComment response = new StashPullRequestComment();
    response.setCommentId(3);

    List<StashPullRequestComment> comments = Arrays.asList(comment2, comment1);
    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    when(trigger.getDeletePreviousBuildFinishComments()).thenReturn(true);
    when(trigger.getStashHost()).thenReturn("StashHost");
    when(project.getFullName()).thenReturn("MyProject");

    pullRequest.setId("123");
    stashRepository.addFutureBuildTasks(pullRequestList);

    verify(stashApiClient, times(1)).deletePullRequestComment(eq("123"), eq("1"));
    verify(stashApiClient, times(0)).deletePullRequestComment(eq("123"), eq("2"));
  }

  @Test
  public void addFutureBuildTasks_schedules_build_if_deletePullRequestComment_throws()
      throws Exception {
    StashPullRequestComment comment = new StashPullRequestComment();
    comment.setCommentId(1);
    comment.setText("[*BuildFinished* **MyProject**] DEF2 into DEF1");
    List<StashPullRequestComment> comments = Collections.singletonList(comment);

    StashPullRequestComment response = new StashPullRequestComment();
    response.setCommentId(2);

    when(stashApiClient.getPullRequestComments(any(), any(), any())).thenReturn(comments);
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    when(trigger.getDeletePreviousBuildFinishComments()).thenReturn(true);
    when(trigger.getStashHost()).thenReturn("http://localhost/");
    when(project.getFullName()).thenReturn("MyProject");
    doThrow(new StashApiException("Cannot Delete"))
        .when(stashApiClient)
        .deletePullRequestComment(any(), any());

    stashRepository.addFutureBuildTasks(pullRequestList);

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(arrayWithSize(1)));
  }

  @Test
  public void addFutureBuildTasks_doesnt_schedule_build_if_postPullRequestComment_throws()
      throws Exception {
    when(stashApiClient.postPullRequestComment(any(), any()))
        .thenThrow(new StashApiException("Cannot Post"));

    stashRepository.addFutureBuildTasks(pullRequestList);

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
    assertThat(logLines[2], is(equalTo("Number of pull requests to be built: 0")));
    assertThat(logLines[3], containsString(": poll completed in "));
  }

  @Test
  public void pollRepository_schedules_build_for_open_pull_request() throws Exception {
    StashPullRequestComment response = new StashPullRequestComment();
    response.setCommentId(1);

    when(trigger.getStashHost()).thenReturn("StashHost");
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(stashApiClient.getPullRequestComments(any(), any(), any()))
        .thenReturn(Collections.emptyList());
    when(stashApiClient.postPullRequestComment(any(), any())).thenReturn(response);

    stashRepository.pollRepository();

    assertThat(Jenkins.getInstance().getQueue().getItems(), is(arrayWithSize(1)));
  }

  @Test
  public void startJob_passes_parameter_with_default_value() {
    cause = makeCause(null);
    ParameterDefinition parameterDefinition =
        new StringParameterDefinition("param1", "param1_default");
    jobSetup(parameterDefinition);

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

    ParameterDefinition parameterDefinition =
        new BooleanParameterDefinition("param1", false, "parameter 1");
    jobSetup(parameterDefinition);

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, contains(new StringParameterValue("param1", "param1_value")));
  }

  @Test
  public void startJob_skips_null_parameters() {
    cause = makeCause(null);

    ParameterDefinition parameterDefinition = new FileParameterDefinition("param1", "parameter 1");
    jobSetup(parameterDefinition);

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(parameters, is(empty()));
  }

  @Test
  public void parameters_populated_from_StashCause() {
    cause = makeCause(null);

    ParameterDefinition parameterDefinition =
        new StringParameterDefinition("sourceBranch", "DefaultBranch");
    jobSetup(parameterDefinition);

    List<ParameterValue> parameters = captureBuildParameters();

    assertThat(
        parameters, contains(new StringParameterValue("sourceBranch", cause.getSourceBranch())));
  }

  @Test
  public void parameters_from_pull_request_comments_overridden_by_StashCause() {
    Map<String, String> prParameters = new TreeMap<>();
    prParameters.put("sourceBranch", "BranchFromPrComment");
    cause = makeCause(prParameters);

    ParameterDefinition parameterDefinition =
        new StringParameterDefinition("sourceBranch", "DefaultBranch");
    jobSetup(parameterDefinition);

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
  public void getTargetPullRequests_skips_pull_request_if_getPullRequestComments_throws()
      throws Exception {
    when(stashApiClient.getPullRequests()).thenReturn(pullRequestList);
    when(stashApiClient.getPullRequestComments(any(), any(), any()))
        .thenThrow(new StashApiException("cannot read PR comments"));

    assertThat(stashRepository.getTargetPullRequests(), is(empty()));
  }
}
