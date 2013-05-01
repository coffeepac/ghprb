
package org.jenkinsci.plugins.ghprb;

import hudson.model.ProminentProjectAction;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.stapler.StaplerRequest;

/**
 * @author janinko
 */
public class GhprbProjectAction implements ProminentProjectAction{

	private static final Logger logger = Logger.getLogger(GhprbProjectAction.class.getName());

	static final String URL = "ghprbhook";
	private GhprbGitHub gh;
	private GhprbRepository repo;

	public GhprbProjectAction(GhprbTrigger trigger){
		repo = trigger.getGhprb().getRepository();
		gh = trigger.getGhprb().getGitHub();
	}

	public String getIconFileName() {
		return null;
	}

	public String getDisplayName() {
		return null;
	}

	public String getUrlName() {
		return URL;
	}

	public void doIndex(StaplerRequest req) {
		logger.log(Level.INFO, "Receiving...");
		String event = req.getHeader("X-Github-Event");
		String payload = req.getParameter("payload");
		if(payload == null){
			logger.log(Level.SEVERE, "Request doesn't contain payload.");
			return;
		}
		try{
			if("issue_comment".equals(event)){
				logger.log(Level.INFO, "issue_comment");
				GHEventPayload.IssueComment issueComment = gh.get().parseEventPayload(new StringReader(payload), GHEventPayload.IssueComment.class);
				logger.log(Level.INFO, issueComment.toString());
				repo.onIssueCommentHook(issueComment);
			}else if("pull_request".equals(event)) {
				logger.log(Level.INFO, "pull_request");
				GHEventPayload.PullRequest pr = gh.get().parseEventPayload(new StringReader(payload), GHEventPayload.PullRequest.class);
				logger.log(Level.INFO, pr.toString());
				repo.onPullRequestHook(pr.getAction(), pr.getNumber(), pr.getPullRequest());
			}else{
				logger.log(Level.WARNING, "Request not known");
			}
		}catch(IOException ex){
			logger.log(Level.SEVERE, "Failed to parse github hook payload.", ex);
		}
	}
}
