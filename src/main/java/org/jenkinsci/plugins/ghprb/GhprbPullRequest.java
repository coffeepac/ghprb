package org.jenkinsci.plugins.ghprb;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbPullRequest{

	private static final Logger logger = Logger.getLogger(GhprbPullRequest.class.getName());

	private final int id;
	private final String author;
	private Date updated;
	private String head;
	private boolean mergeable;
	private String reponame;
	private String target;

	private boolean shouldRun = false;
	private boolean accepted = false;
	@Deprecated private transient boolean askedForApproval; // TODO: remove

	private transient Ghprb ml;
	private transient GhprbRepository repo;

	GhprbPullRequest(GHPullRequest pr, Ghprb helper, GhprbRepository repo) {
		id = pr.getNumber();
		updated = pr.getUpdatedAt();
		head = pr.getHead().getSha();
		author = pr.getUser().getLogin();
		reponame = repo.getName();
		target = pr.getBase().getRef();

		this.ml = helper;
		this.repo = repo;

		if (helper.isWhitelisted(author)) {
			accepted = true;
			shouldRun = true;
		} else {
			logger.log(Level.INFO, "Author of #{0} {1} on {2} not in whitelist!", new Object[]{id, author, reponame});
			repo.addComment(id, GhprbTrigger.getDscp().getRequestForTestingPhrase());
		}

		logger.log(Level.INFO, "{0} created; author: {1}, updated: {2} SHA: {3}, accepted: {4}", new Object[]{ this.toString(), author, updated, head, accepted });
	}

	public void init(Ghprb helper, GhprbRepository repo) {
		this.ml = helper;
		this.repo = repo;
	}

	public void check(GHPullRequest pr){
		if(target == null) target = pr.getBase().getRef(); // If this instance was created before target was introduced (before v1.8), it can be null.

		if(isUpdated(pr)){
			logger.log(Level.INFO, "{0} has been updated", new Object[]{ this.toString() });
			try {
				int commentsChecked = checkComments(pr.getComments());
				boolean newCommit   = checkCommit(pr.getHead().getSha());
				if (!newCommit && commentsChecked == 0) {
					logger.log(Level.INFO, "{0} was updated but there appears to be no new commit or comments - that may mean that commit status was updated.", new Object[]{ this.toString() });
				}
			} catch (IOException ex) {
				logger.log(Level.WARNING, "{0} unable to retrieve comments", ex);
			}
			updated = pr.getUpdatedAt();
		}

		if(shouldRun){
			checkMergeable(pr);
			build();
		}
	}

	public void check(GHIssueComment comment) {
		try {
			checkComment(comment);
			updated = comment.getUpdatedAt();
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Couldn't check comment #" + comment.getId(), ex);
			return;
		}
		if (shouldRun) {
			build();
		}
	}

	private boolean isUpdated(GHPullRequest pr){
		boolean ret = false;
		ret = ret || updated.compareTo(pr.getUpdatedAt()) < 0;
		ret = ret || !pr.getHead().getSha().equals(head);

		logger.log(Level.INFO, "{0} isUpdated: {7}, updatedCheck: {1} (updated: {2}, pr.updatedAt: {3}), headCheck: {4} (head: {5}, pr.sha: {6})",
				new Object[]{ this.toString(), (updated.compareTo(pr.getUpdatedAt()) < 0), updated, pr.getUpdatedAt(),
						(!pr.getHead().getSha().equals(head)), head, pr.getHead().getSha(), ret });

		return ret;
	}

	private void build(){
		shouldRun = false;
		String message = ml.getBuilds().build(this);

		repo.createCommitStatus(head, GHCommitState.PENDING, null, message,id);

		logger.log(Level.INFO, message);
	}

	// returns false if no new commit
	private boolean checkCommit(String sha) {
		if (head.equals(sha)) {
			return false;
		}

		if (logger.isLoggable(Level.FINE)) {
			logger.log(Level.FINE, "New commit. Sha: {0} => {1}", new Object[]{ head, sha });
		}

		head = sha;

		if (accepted) {
			shouldRun = true;
		}

		return true;
	}

	private void checkComment(GHIssueComment comment) throws IOException {
		String sender = comment.getUser().getLogin();
		String body = comment.getBody();

		logger.log(Level.INFO, "{0} checking comment; sender: {1}, body: {2}", new Object[] { this.toString(), sender, body });

		// add to whitelist
		if (ml.isWhitelistPhrase(body) && ml.isAdmin(sender)){
			logger.log(Level.INFO, "{0} adding author {1} to whitelist per comment by {2}", new Object[] { this.toString(), author, sender});
			if(!ml.isWhitelisted(author)) {
				ml.addWhitelist(author);
			}
			accepted = true;
			shouldRun = true;
		}

		// ok to test
		if(ml.isOktotestPhrase(body) && ml.isAdmin(sender)){
			logger.log(Level.INFO, "{0} ok to test per comment by {2}", new Object[] { this.toString(), author, sender});
			accepted = true;
			shouldRun = true;
		}

		// test this please
		if (ml.isRetestPhrase(body)){
			if(ml.isAdmin(sender)){
				shouldRun = true;
			}else if(accepted && ml.isWhitelisted(sender) ){
				shouldRun = true;
			}
			if (shouldRun) {
				logger.log(Level.INFO, "{0} should be retested per comment by {2}", new Object[] { this.toString(), author, sender});
			}
		}
	}

	private int checkComments(List<GHIssueComment> comments) throws IOException {
		logger.log(Level.INFO, "{0} checking comments {1}", new Object[]{ this.toString(), comments });
		int count = 0;
		for (GHIssueComment comment : comments) {
			logger.log(Level.INFO, "DEBUG {0} commentCheck: {1} (updated: {2}, comment.updatedAt: {3})", new Object[]{ this.toString(), (updated.compareTo(comment.getUpdatedAt()) < 0), updated, comment.getUpdatedAt()});
			if (updated.compareTo(comment.getUpdatedAt()) < 0) {
				count++;
				checkComment(comment);
			}
		}
		return count;
	}

	private void checkMergeable(GHPullRequest pr) {
		try {
			mergeable = pr.getMergeable();
		} catch (IOException e) {
			mergeable = false;
			logger.log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
		}
	}

	@Override
	public String toString() {
		return ((repo == null) ? "null" : repo.getName()) + "#" + this.id;
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof GhprbPullRequest)) return false;

		GhprbPullRequest o = (GhprbPullRequest) obj;
		return o.id == id;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 89 * hash + this.id;
		return hash;
	}

	public int getId() {
		return id;
	}

	public String getHead() {
		return head;
	}

	public boolean isMergeable() {
		return mergeable;
	}

	public String getTarget(){
		return target;
	}
}
