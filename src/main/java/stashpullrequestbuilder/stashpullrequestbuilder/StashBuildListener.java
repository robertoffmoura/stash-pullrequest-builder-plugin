package stashpullrequestbuilder.stashpullrequestbuilder;

import static java.lang.String.format;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.apache.commons.lang.StringUtils;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashApiClient.StashApiException;

/** Created by Nathan McCarthy */
@Extension
public class StashBuildListener extends RunListener<Run<?, ?>> {
  private static final Logger logger =
      Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

  @Override
  public void onStarted(Run<?, ?> run, TaskListener listener) {
    StashCause cause = run.getCause(StashCause.class);
    if (cause == null) {
      return;
    }

    logger.info(
        format(
            "%s started for PR #%s, commit %s",
            run, cause.getPullRequestId(), cause.getSourceCommitHash()));

    try {
      run.setDescription(cause.getShortDescription());
    } catch (IOException e) {
      PrintStream buildLogger = listener.getLogger();
      buildLogger.println("Can't update build description");
      e.printStackTrace(buildLogger);
    }
  }

  @Override
  public void onCompleted(Run<?, ?> run, @Nonnull TaskListener listener) {
    PrintStream buildLogger = listener.getLogger();

    StashCause cause = run.getCause(StashCause.class);
    if (cause == null) {
      return;
    }

    StashBuildTrigger trigger =
        ParameterizedJobMixIn.getTrigger(run.getParent(), StashBuildTrigger.class);
    if (trigger == null) {
      return;
    }

    StashRepository repository = trigger.getRepository();
    Result result = run.getResult();

    // Make a link to the build page in Jenkins. If Jenkins root URL is not
    // configured, post a message that won't show as a link to indicate the
    // problem. The build status would still be visible in that case.
    final String rootUrl =
        Objects.toString(
            Jenkins.getInstance().getRootUrl(),
            "=== PLEASE SET JENKINS ROOT URL FROM GLOBAL CONFIGURATION ===");
    final String buildUrl = rootUrl + run.getUrl();

    // Delete the "Build Started" comment, as it gets replaced with a comment
    // about the build result. Failure to delete the comment is not fatal, it's
    // reported to the build log.
    try {
      repository.deletePullRequestComment(cause.getPullRequestId(), cause.getBuildStartCommentId());
    } catch (StashApiException e) {
      buildLogger.println(
          format(
              "%s: cannot delete Build Start comment for pull request %s",
              run.getParent().getFullName(), cause.getPullRequestId()));
      e.printStackTrace(buildLogger);
    }

    String additionalComment = "";
    StashPostBuildComment comments = null;

    if (run instanceof AbstractBuild) {
      AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;
      comments = build.getProject().getPublishersList().get(StashPostBuildComment.class);
    }

    if (comments != null) {
      String buildComment =
          result == Result.SUCCESS
              ? comments.getBuildSuccessfulComment()
              : comments.getBuildFailedComment();

      if (StringUtils.isNotEmpty(buildComment)) {
        String expandedComment;
        try {
          expandedComment = Util.fixEmptyAndTrim(run.getEnvironment(listener).expand(buildComment));
        } catch (IOException | InterruptedException e) {
          expandedComment = "Exception while expanding '" + buildComment + "': " + e;
        }

        additionalComment = "\n\n" + expandedComment;
      }
    }
    String duration = run.getDurationString();
    repository.postFinishedComment(
        cause.getPullRequestId(),
        cause.getSourceCommitHash(),
        cause.getDestinationCommitHash(),
        cause.getBuildCommandCommentId(),
        result,
        buildUrl,
        run.getNumber(),
        additionalComment,
        duration);

    // Request the server to merge the pull request on success if that option is
    // enabled. Log the result to the build log. Handle exceptions here, report
    // them to the build log as well.
    if (trigger.getMergeOnSuccess() && run.getResult() == Result.SUCCESS) {
      try {
        Optional<String> mergeError =
            repository.mergePullRequest(cause.getPullRequestId(), cause.getPullRequestVersion());

        if (!mergeError.isPresent()) {
          buildLogger.println(
              format(
                  "Successfully merged pull request %s (%s) to branch %s",
                  cause.getPullRequestId(), cause.getSourceBranch(), cause.getTargetBranch()));
        } else {
          buildLogger.println(
              format(
                  "Failed to merge pull request %s (%s) to branch %s, error message:",
                  cause.getPullRequestId(), cause.getSourceBranch(), cause.getTargetBranch()));
          buildLogger.println(mergeError.get());
        }
      } catch (StashApiException e) {
        buildLogger.println(
            format(
                "Failed to merge pull request %s (%s) to branch %s",
                cause.getPullRequestId(), cause.getSourceBranch(), cause.getTargetBranch()));
        e.printStackTrace(buildLogger);
      }
    }
  }
}
