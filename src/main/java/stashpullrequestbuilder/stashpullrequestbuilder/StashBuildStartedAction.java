package stashpullrequestbuilder.stashpullrequestbuilder;

import hudson.model.InvisibleAction;

/**
 * Action attached to a build run to carry the "BuildStarted" comment ID from {@link
 * StashBuildListener#onStarted} to {@link StashBuildListener#onCompleted}, so the comment can be
 * deleted when the build finishes.
 */
public class StashBuildStartedAction extends InvisibleAction {
  private final String commentId;

  public StashBuildStartedAction(String commentId) {
    this.commentId = commentId;
  }

  public String getCommentId() {
    return commentId;
  }
}
