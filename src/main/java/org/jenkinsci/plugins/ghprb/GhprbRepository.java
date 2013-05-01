package org.jenkinsci.plugins.ghprb;

import hudson.model.AbstractBuild;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.github.*;
import org.kohsuke.github.GHEventPayload.IssueComment;
import org.kohsuke.github.GHEventPayload.PullRequest;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbRepository {

	private static final Logger logger = Logger.getLogger(GhprbRepository.class.getName());

	private final String reponame;

	private Map<Integer,GhprbPullRequest> pulls;

	private GHRepository repo;
	private Ghprb ml;

	public GhprbRepository(String user,
	                 String repository,
	                 Ghprb helper,
	                 Map<Integer,GhprbPullRequest> pulls){
		reponame = user + "/" + repository;
		this.ml = helper;
		this.pulls = pulls;
	}

	public void init(){
		checkState();
		for(GhprbPullRequest pull : pulls.values()){
			pull.init(ml,this);
		}
	}

	private boolean checkState(){
		if (repo == null) {
			try {
				repo = ml.getGitHub().get().getRepository(reponame);
			} catch (IOException ex) {
				logger.log(Level.SEVERE, "Could not retrieve repo named " + reponame + " (Do you have properly set 'GitHub project' field in job configuration?)", ex);
				return false;
			}
		}
		return true;
	}

	public void check() {
		logger.log(Level.INFO, "Repo {0} checking pull requests", new Object[] { reponame });

		if (!checkState()) {
			return;
		}

		List<GHPullRequest> prs;
		try {
			prs = repo.getPullRequests(GHIssueState.OPEN);
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Could not retrieve pull requests.", ex);
			return;
		}
		Set<Integer> closedPulls = new HashSet<Integer>(pulls.keySet());

		for (GHPullRequest pr : prs) {
			check(pr);
			closedPulls.remove(pr.getNumber());
		}

		removeClosed(closedPulls, pulls);
	}

	private void check(GHPullRequest pr){
			Integer id = pr.getNumber();
			GhprbPullRequest pull;
			if(pulls.containsKey(id)){
				pull = pulls.get(id);
			}else{
				pull = new GhprbPullRequest(pr, ml, this);
				pulls.put(id, pull);
			}
			pull.check(pr);
	}

	private void removeClosed(Set<Integer> closedPulls, Map<Integer,GhprbPullRequest> pulls) {
		if(closedPulls.isEmpty()) return;

		for(Integer id : closedPulls){
			pulls.remove(id);
		}
	}

	public void createCommitStatus(AbstractBuild<?,?> build, GHCommitState state, String message, int id){
		String sha1 = build.getCause(GhprbCause.class).getCommit();
		createCommitStatus(sha1, state, Jenkins.getInstance().getRootUrl() + build.getUrl(), message, id);
	}

	public void createCommitStatus(String sha1, GHCommitState state, String url, String message, int id) {
		logger.log(Level.INFO, "Setting status of {0} to {1} with url {2} and message: {3}", new Object[]{sha1, state, url, message});
		try {
			repo.createCommitStatus(sha1, state, url, message);
		} catch (IOException ex) {
			if(GhprbTrigger.getDscp().getUseComments()){
				logger.log(Level.INFO, "Could not update commit status of the Pull Request on Github. Trying to send comment.", ex);
				addComment(id, message);
			}else{
				logger.log(Level.SEVERE, "Could not update commit status of the Pull Request on Github.", ex);
			}
		}
	}

	public String getName() {
		return reponame;
	}

	public void addComment(int id, String comment) {
		try {
			repo.getPullRequest(id).comment(comment);
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Couldn't add comment to pullrequest #" + id + ": '" + comment + "'", ex);
		}
	}

	public void closePullRequest(int id) {
		try {
			repo.getPullRequest(id).close();
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Couldn't close the pullrequest #" + id + ": '", ex);
		}
	}

	public String getRepoUrl(){
		return ml.getGitHubServer()+"/"+reponame;
	}


	private static final EnumSet EVENTS = EnumSet.of(GHEvent.ISSUE_COMMENT, GHEvent.PULL_REQUEST);

	private boolean hookExists() throws IOException {
		for (GHHook h : repo.getHooks()) {
			if ("web".equals(h.getName()) && ml.getHookUrl().equals(h.getConfig().get("url"))) {
				return true;
			}
		}
		return false;
	}

	public boolean createHook() {
		try {
			if (hookExists()) {
				return true;
			}
			Map<String,String> config = new HashMap<String, String>();
			config.put("url", new URL(ml.getHookUrl()).toExternalForm());
			if (!GhprbTrigger.getDscp().isVerifyHookUrlSsl()) {
				config.put("insecure_ssl", "1");
			}
			repo.createHook("web", config, EVENTS, true);
			return true;
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Couldn't create web hook for repository"+reponame , ex);
			return false;
		}
	}

	void onIssueCommentHook(IssueComment issueComment) {
		String action = issueComment.getAction();
		GHIssue issue = issueComment.getIssue();
		int number = issue.getNumber();
		GHIssueComment comment = issueComment.getComment();
		String body = comment.getBody();
		logger.log(Level.INFO, "Repo {0} issueComment hook; action: {1}, issue.number: {2}, comment.body: {3}", new Object[] { reponame, action, number, body });
		if ("created".equals(action)) {
			if (issue.getPullRequest() != null) {
				try {
					GHPullRequest pullRequest = repo.getPullRequest(number);
					if (pullRequest.getState() == GHIssueState.OPEN) {
						// Transmogrify this into a "pull request synchronize" event; commits may have changed since we last saw the pull request
						onPullRequestHook("synchronize", number, pullRequest);
					}
				} catch (IOException e) {
					logger.log(Level.WARNING, "Failed to convert unknown issue into pull request", e);
				}
			}
		} else {
			logger.log(Level.WARNING, "Unknown action: {0}", new Object[] { action });
		}
		GhprbTrigger.getDscp().save();
	}

	void onPullRequestHook(String action, int number, GHPullRequest pullRequest) {
		logger.log(Level.INFO, "Repo {0} pullRequest hook; action: {1}, number: {2}, pullRequest: {3}", new Object[] { reponame, action, number, pullRequest });
		if ("opened".equals(action) || "reopened".equals(action) || "synchronize".equals(action)) {
			GhprbPullRequest pull = pulls.get(number);
			if (pull == null) {
				logger.log(Level.INFO, "Creating new GhprbPullRequest for pr.number: {0}", new Object[] { number });
				pull = new GhprbPullRequest(pullRequest, ml, this);
				pulls.put(number, pull);
			}
			pull.check(pullRequest);
		} else if ("closed".equals(action)) {
			logger.log(Level.INFO, "Removing GhprbPullRequest for pr.number: {0}", new Object[] { number });
			pulls.remove(number);
		} else {
			logger.log(Level.WARNING, "Unknown action: {0}", new Object[] { action });
		}
		GhprbTrigger.getDscp().save();
	}
}
