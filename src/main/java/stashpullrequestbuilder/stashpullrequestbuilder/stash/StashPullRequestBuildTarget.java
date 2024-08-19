package stashpullrequestbuilder.stashpullrequestbuilder.stash;

import java.util.Map;
import java.util.TreeMap;

public class StashPullRequestBuildTarget {
  private StashPullRequestResponseValue pullRequest;
  private Map<String, String> additionalParameters;
  private Integer buildCommandCommentId;

  public StashPullRequestBuildTarget(StashPullRequestResponseValue pullRequest) {
    this.pullRequest = pullRequest;
    this.additionalParameters = new TreeMap<>();
  }

  public StashPullRequestBuildTarget(
      StashPullRequestResponseValue pullRequest, Map<String, String> additionalParameters) {
    this.pullRequest = pullRequest;
    this.additionalParameters = additionalParameters;
  }

  public StashPullRequestBuildTarget(
      StashPullRequestResponseValue pullRequest,
      Map<String, String> additionalParameters,
      Integer buildCommandCommentId) {
    this.pullRequest = pullRequest;
    this.additionalParameters = additionalParameters;
    this.buildCommandCommentId = buildCommandCommentId;
  }

  public StashPullRequestResponseValue getPullRequest() {
    return pullRequest;
  }

  public Map<String, String> getAdditionalParameters() {
    return additionalParameters;
  }

  public Integer getBuildCommandCommentId() {
    return buildCommandCommentId;
  }
}
