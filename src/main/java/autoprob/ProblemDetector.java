package autoprob;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneConnect;
import autoprob.go.action.TriangleAction;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

public class ProblemDetector {
    protected static final DecimalFormat df = new DecimalFormat("0.00");
	
//	public static final int DETECT_SCORE = 18;
	public static final double OWNERSHIP_THRESHOLD = 1.5;
	public static final double EMPTY_OWNERSHIP_THRESHOLD = 0.7;
	public static int DETECT_OWNERSHIP_STONES = 7;
	public static final double EXTRA_SOLUTION_THRESHOLD = 10; // scores within this range
	public static int DETECT_MAX_SOLUTIONS = 1;
	public static final double MIN_SOL_VISIT_RATIO = 0.05;
	public static double MAX_POLICY; // anything over this is just toooo obvious
	protected final Properties props;

	public boolean validProblem;
	
	KataAnalysisResult mistake;
	KataAnalysisResult prev;
	public double scoreDelta;
	public double highestPrior = 0;
	public int totDelta;
	public int numSols;
	protected ArrayList<String> sols = new ArrayList<>();
	Node node;
	public String solString;
	public int ownDeltaB;
	public int ownDeltaW;
	public ArrayList<Point> ownershipChanges = new ArrayList<>();
	public ArrayList<Point> fullOwnershipChanges = new ArrayList<>(); // includes empty intersections
	public ArrayList<Point> fullOwnNeighbors;
	
	public Board filledStones = new Board(); // what we have placed down to fill
	
	Node problem;

	public KataAnalysisResult karPass;

	// prev is the problem position. kar is the mistake position. node represents prev.
	// the constructor tries to detect a problem. if it does, validProblem is set to true.
	// @forcedetect: if true, ignore score and ownership thresholds etc
	public ProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node node, Properties props) throws Exception {
		this.mistake = mistake;
		this.prev = prev;
		this.node = node;
		this.props = props;

		// set from properties
		MAX_POLICY = Double.parseDouble(props.getProperty("search.max_policy"));
		DETECT_MAX_SOLUTIONS = Integer.parseInt(props.getProperty("search.max_solutions"));
		DETECT_OWNERSHIP_STONES = Integer.parseInt(props.getProperty("search.life_mistake_stones"));

		validProblem = false;
	}

	public void detectProblem(KataBrain brain, boolean forceDetect) throws Exception {
        Node child = node.favoriteSon();
        Point nextMove = child.findMove();
        if (nextMove.x == 19) return;

		// possibly check score
		double minScoreDelta = Double.parseDouble(props.getProperty("search.min_score_delta", "0"));
		if (minScoreDelta > 0) {
			// big score differential?
			scoreDelta = mistake.rootInfo.scoreLead - prev.rootInfo.scoreLead;
			if (Math.abs(scoreDelta) < minScoreDelta) {
				System.out.println("score delta too small: " + Math.abs(scoreDelta));
				if (!forceDetect) return;
			}
		}

		double minAbsoluteScore = Double.parseDouble(props.getProperty("search.min_absolute_score", "0"));
		if (minAbsoluteScore > 0) {
			// needs to go from clearly winning to clearly losing
			double score = prev.rootInfo.scoreLead;
			if (Math.abs(score) < minAbsoluteScore) {
				System.out.println("score before too small: " + Math.abs(score));
				if (!forceDetect) return;
			}
			// check for after
			score = mistake.rootInfo.scoreLead;
			if (Math.abs(score) > minAbsoluteScore) {
				System.out.println("score after too big: " + Math.abs(score));
				if (!forceDetect) return;
			}
		}

		// ownership delta: what dies in this mistake?
		stoneDelta(mistake, node, prev); // also saves in ownershipChanges
		if (totDelta < DETECT_OWNERSHIP_STONES) {
//			System.out.println("low ownership change: " + (totDelta));
			if (!forceDetect) return;
		}

		numSols = countSolutions(prev);
		if (numSols > DETECT_MAX_SOLUTIONS) {
			System.out.println("too many sols: " + numSols);
			if (!forceDetect) return;
		}
		
		// skip problems where previous move was ko
		Point lastMove = node.findMove();
		if (lastMove.x != 19 && node.mom.board.isKoShape(lastMove)) {
			System.out.println("last move was ko");
			if (Boolean.parseBoolean(props.getProperty("search.no_last_ko_move"))) {
				if (!forceDetect) return;
			}
		}
		// skip problems that involve ko as much as possible
		if (nextMove.x != 19 && node.board.isKoShape(nextMove)) {
			System.out.println("mistake was ko");
			if (!forceDetect) return;
		}
		// check sols too
		for (String mv: sols) {
			Point p = Intersection.gtp2point(mv);
			if (node.board.isKoShape(p)) {
				System.out.println("sol was ko: " + mv);
				if (!forceDetect) return;
			}
		}
		
		// max policy
		if (highestPrior > MAX_POLICY) {
			System.out.println("too high policy: " + highestPrior);
			if (!forceDetect) return;
		}
		
		// let's do a more exhaustive analysis here
		int visits = Integer.parseInt(props.getProperty("search.root_visits"));
		System.out.println("running deeper problem search analysis with #visits: " + visits);
		boolean dbgOwn = Boolean.parseBoolean(props.getProperty("search.debug_pass_ownership", "false"));
		var na = new NodeAnalyzer(props, dbgOwn);
		// first the root node, the position before the mistake
		var karDeep = na.analyzeNode(brain, node, visits);

		// compare what happens if we pass

		Node passNode = node.addBasicMove(19, 19);
		System.out.println("doing more exhaustive check, visits=" + visits);
		karPass = na.analyzeNode(brain, passNode, visits);
		// clean up
		node.removeChildNode(passNode);

		//TODO count sols again etc

		// redo delta with more accurate analysis
		stoneDelta(karPass, node, karDeep); // also saves in ownershipChanges
		if (totDelta < DETECT_OWNERSHIP_STONES) {
			System.out.println("  deep search low ownership change: " + (totDelta));
			if (!forceDetect) return;
		}

		// calc delta including empties
		calcFullDelta(karPass, node, karDeep);

		// check if some stones are not as clearly live/dead as we would like
		int ownershipChangesUnderThreshold = countOwnershipChangesUnderThreshold(karDeep);
		System.out.println("ownership change stones under life threshold: " + ownershipChangesUnderThreshold);
		if (ownershipChangesUnderThreshold >= Integer.parseInt(props.getProperty("search.min_alive_threshold_stones"))) {
			if (!forceDetect) return;
		}

		System.out.println("Detected prob at move: " + prev.turnNumber);
		validProblem = true;
		
		// construct a new board position with relevant stones
		makeProblem();
	}

	// for testing
	public ProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node n, Properties props, boolean b) {
		this.mistake = mistake;
		this.prev = prev;
		this.node = n;
		this.props = props;

		makeProblem();
	}

	// from the ownership changes, check how many stones are in a not super clear state
	protected int countOwnershipChangesUnderThreshold(KataAnalysisResult kar) {
		int count = 0;
		double minAliveThreshold = Double.parseDouble(props.getProperty("search.min_alive_threshold"));

		// loop over just stones
		for (Point p: ownershipChanges) {
			double aliveness = kar.ownership.get(p.x + p.y * 19);
			// debug print
			System.out.println("  aliveness: " + aliveness + " at " + Intersection.toGTPloc(p.x, p.y));
			if (Math.abs(aliveness) < minAliveThreshold) {
				count++;
			}
		}
		return count;
	}

	// create initial position and paths
	protected void makeProblem() {
		problem = new Node(null);
		
		// add some metadata, give credit
		problem.addXtraTag("AP", "goproblems autoextractor");
		copyTag("GN", node, problem);
		copyTag("PW", node, problem);
		copyTag("PB", node, problem);
		copyTag("PC", node, problem); // game info: place
		copyTag("DT", node, problem);
		copyTag("GN", node, problem); // game name

		// set who is to move
		problem.defaultToMoveColor = node.getToMove();
		// helps some editors
		problem.addXtraTag("PL", problem.defaultToMoveColor == Intersection.BLACK ? "B" : "W");
		
		makeProblemStones();
	}

	// figure out what stones should be on our problem board
	protected void makeProblemStones() {
		boolean fullBoard = Boolean.parseBoolean(props.getProperty("extract.full_board", "false"));
		// if full board, copy every stone
		if (fullBoard) {
			for (int x = 0; x < 19; x++)
				for (int y = 0; y < 19; y++) {
					problem.board.board[x][y].stone = node.board.board[x][y].stone;
				}
		}
		else {
			StoneConnect scon = new StoneConnect();
			// copy ownership change stones
			for (Point p : ownershipChanges) {
				stone2problem(p);
				addNeighbors(scon, p, true);
			}

			System.out.println("add full ownership change stones to problem....");
			// full ownership change stones
			addFromFull(scon);

			System.out.println("make from near...");
			// near solutions
			double baseline = prev.rootInfo.scoreLead;
			int solDist = 1;
			for (MoveInfo mi : prev.moveInfos) {
				double score = mi.scoreLead;
				if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
					String spos = mi.move;
//						System.out.println("p " + spos);
					Point p = Intersection.gtp2point(spos);
					if (p.x == 19) continue;
					stone2problem(p);
					for (int dx = -solDist; dx <= solDist; dx++) {
						for (int dy = -solDist; dy <= solDist; dy++) {
							int x = p.x + dx;
							int y = p.y + dy;
							if (scon.isOnboard(x, y))
								addNeighbors(scon, new Point(x, y), true);
						}
					}
				}
			}
		}

		// if the previous move stone got placed, let's mark it by default
		Point lastMove = node.findMove();
		if (lastMove != null && lastMove.x != 19) {
			if (problem.board.board[lastMove.x][lastMove.y].stone != Intersection.EMPTY) {
				problem.addAct(new TriangleAction(lastMove.x, lastMove.y));
			}
		}
	}

	protected void copyTag(String nm, Node src, Node dest) {
		String s = src.getRoot().getXtra(nm);
		if (s != null)
			dest.addXtraTag(nm, s);
	}

	protected void addFromFull(StoneConnect scon) {
		fullOwnNeighbors = scon.floodGroup(ownershipChanges, fullOwnershipChanges, node.board);
		for (Point pn: fullOwnNeighbors) {
//			System.out.println("flooded: " + Intersection.toGTPloc(pn.x,  pn.y, 19));
			stone2problem(pn);
			addNeighbors(scon, pn, true);
		}
	}

	protected void addNeighbors(StoneConnect scon, Point p, boolean alsoConnected) {
		ArrayList<Point> neighbs = scon.calcNeighbors(p, node.board);
		for (Point pn: neighbs) {
			stone2problem(pn);
			if (alsoConnected) {
				var cons = scon.calcConnected(pn, node.board);
				for (Point pc: cons) {
//					System.out.println("from: " + Intersection.toGTPloc(pn.x,  pn.y, 19) + " to con: " + Intersection.toGTPloc(pc.x,  pc.y, 19));
					stone2problem(pc);
				}
			}
		}
	}

	protected void stone2problem(Point p) {
		int stn = node.board.board[p.x][p.y].stone;
		problem.board.board[p.x][p.y].stone = stn;
	}

	// what stone ownership changes significantly between these moves
	// store delta in ownershipChanges
	public void stoneDelta(KataAnalysisResult kar, Node node, KataAnalysisResult prev) {
		// reset any existing data
		ownDeltaB = ownDeltaW = totDelta = 0;
		ownershipChanges.clear();
		double maxDelta = 0;

		double threshold = Double.parseDouble(props.getProperty("search.ownership_threshold"));

		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				double od = kar.ownership.get(x + y * 19) - prev.ownership.get(x + y * 19);
				int stn = node.board.board[x][y].stone;
				if (stn == 0) continue;
				maxDelta = Math.max(maxDelta, Math.abs(od));
				if (Math.abs(od) > threshold) {
					System.out.println("own delta: " + df.format(od) + ", " + Intersection.toGTPloc(x, y, 19) +
							" (" + df.format(prev.ownership.get(x + y * 19)) + " -> " + df.format(kar.ownership.get(x + y * 19)) + ")");
					if (stn == Intersection.BLACK)
						ownDeltaB++;
					else
						ownDeltaW++;
					ownershipChanges.add(new Point(x, y));
				}
			}
		boolean dbg = Boolean.parseBoolean(props.getProperty("extract.debug_print_ownership", "false"));
		if (dbg) {
			System.out.println("max ownership delta (stones relatively changing sides): " + df.format(maxDelta));
		}
		totDelta = ownDeltaB + ownDeltaW;
	}

	// what stone ownership changes significantly between these moves
	protected void calcFullDelta(KataAnalysisResult kar, Node node, KataAnalysisResult prev) {
		boolean dbg = Boolean.parseBoolean(props.getProperty("extract.debug_print_ownership", "false"));
		if (dbg) {
			System.out.println("prev ownership:");
			prev.drawNumericalOwnership(node);

			System.out.println("pass ownership:");
			kar.drawNumericalOwnership(node);

			System.out.println("delta ownership:");
		}
		for (int y = 0; y < 19; y++) {
			for (int x = 0; x < 19; x++) {
				// calculate the ownership delta
				double od = kar.ownership.get(x + y * 19) - prev.ownership.get(x + y * 19);

				if (dbg) {
					// print a board representing ownership delta
					// od is between -2 and 2
					double dod = od * 0.5; // to get to single digit
					if (dod > 0.04) {
						int d = (int) (dod * 9.99);
						System.out.print(' ');
						System.out.print(d);
					}
					else if (dod <= -0.1) {
						int d = (int) (dod * 9.99);
						System.out.print(d);
					}
					else
						System.out.print(" .");
					System.out.print(' ');
				}

//				int stn = node.board.board[x][y].stone;
//				if (stn != 0) continue;
				if (Math.abs(od) > EMPTY_OWNERSHIP_THRESHOLD) {
//					System.out.println("full own delta: " + df.format(od) + ", " + Intersection.toGTPloc(x, y, 19) +
//							" (" + df.format(prev.ownership.get(x + y * 19)) + " -> " + df.format(kar.ownership.get(x + y * 19)) + ")");
					fullOwnershipChanges.add(new Point(x, y));
				}
			}
			if (dbg) System.out.println();
		}
		if (dbg) System.out.println();

		System.out.print("full ownership changes = " + fullOwnershipChanges.size() + ": ");
		for (Point p: fullOwnershipChanges) {
			System.out.print(Intersection.toGTPloc(p.x, p.y, 19));
			System.out.print(" ");
		}
		System.out.println();
	}
	
	// how many moves lead to a solution?
	//TODO convert to life/death instead
	protected int countSolutions(KataAnalysisResult kar) {
		double baseline = kar.rootInfo.scoreLead;
		int count = 0;
		solString = "";
		for (MoveInfo mi: kar.moveInfos) {
			double score = mi.scoreLead;
			if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
				if (mi.visits < (int)(MIN_SOL_VISIT_RATIO * kar.rootInfo.visits))
					continue; // too obscure
				count++; // close enough
				System.out.println("sol: " + mi.move);
				if (solString.length() > 0)
					solString = solString + ", ";
				solString = solString + mi.move;
				sols.add(mi.move);
				highestPrior = Math.max(highestPrior, mi.prior);
			}
		}
		return count;
	}
}
