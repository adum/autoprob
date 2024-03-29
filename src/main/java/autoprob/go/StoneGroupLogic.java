package autoprob.go;

import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StoneGroupLogic {
    int[][] offsets = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};

    public boolean isOnboard(int x, int y) {
        return !(x < 0 || y < 0 || x >= 19 || y >= 19);
    }

    private void floodRecurse(int x, int y, Board board, boolean[][] fill, StoneGroup sg) {
        if (fill[x][y]) return;
        if (board.board[x][y].stone != sg.stone) return;

        // new point in group
        fill[x][y] = true;
        sg.stones.add(new Point(x, y));

        for (int i = 0; i < 4; i++) {
            int nx = x + offsets[i][0];
            int ny = y + offsets[i][1];
            if (!isOnboard(nx, ny)) continue;
            floodRecurse(nx, ny, board, fill, sg);
        }
    }

    // create a list of all separate groups on the board, along with ownership
    public List<StoneGroup> groupStones(Board board, KataAnalysisResult kar) {
        // make a 2d array of locations we've checked
        boolean[][] checked = new boolean[19][19];
        var groups = new ArrayList<StoneGroup>();
        // iterate through board
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (checked[x][y]) continue;

                if (board.board[x][y].stone == Intersection.EMPTY) continue;

                // new group
                StoneGroup sg = new StoneGroup(board.board[x][y].stone);
                groups.add(sg);

                // figure out group members
                floodRecurse(x, y, board, checked, sg);
                sg.calculateOwnership(kar);
            }
        }
        return groups;
    }

    // find where a group has gone to
    public StoneGroup findGroupAfterChange(StoneGroup sg, Board board, List<StoneGroup> postChangeGroups) {
        // iterate through groups, look for one where all the stones in the needle are in the haystack
        for (StoneGroup sg2: postChangeGroups) {
            boolean found = true;
            for (Point p: sg.stones) {
                if (!sg2.stones.contains(p)) {
                    found = false;
                    break;
                }
            }
            if (found) return sg2;
        }
    	return null;
    }

    // calculate a group delta
    public double groupDelta(StoneGroup sg, KataAnalysisResult kar) {
    	return sg.ownership - (sg.stone == Intersection.BLACK ? kar.ownership.get(0) : kar.ownership.get(1));
    }

    public static class PointCount {
        public int stone;
        public int count;
        public PointCount(int stone, int count) {
            this.stone = stone;
            this.count = count;
        }
    }

    // flood empty space from a corner, see if it's owned by one player
    private void floodRecurseOwnership(int x, int y, Board board, boolean[][] fill, PointCount pc, KataAnalysisResult kar, double minOwnership) {
        if (fill[x][y]) return;
        if (board.board[x][y].stone != Intersection.EMPTY) return;

        // tried this at this point
        fill[x][y] = true;

        double own = kar.ownership.get(x + y * 19);
        // first check magnitude
        if (Math.abs(own) < minOwnership) return;
        // now make sure it's aligned with original color
        if (own > 0 && pc.stone != Intersection.BLACK) return;

        // new point in group
        pc.count++;

        for (int i = 0; i < 4; i++) {
            int nx = x + offsets[i][0];
            int ny = y + offsets[i][1];
            if (!isOnboard(nx, ny)) continue;
            floodRecurseOwnership(nx, ny, board, fill, pc, kar, minOwnership);
        }
    }

    public PointCount countCornerPoints(Board board, KataAnalysisResult kar) {
        double minOwnership = 0.7; // must be at least this owned by someone
        // flood fill from corners. only one should have anything owned at most
        for (int startx = 0; startx < 19; startx += 18) {
            for (int starty = 0; starty < 19; starty += 18) {
                boolean[][] fill = new boolean[19][19];
                double own = kar.ownership.get(startx + starty * 19);
                PointCount pc = new PointCount(own > 0 ? Intersection.BLACK : Intersection.WHITE, 0);
                floodRecurseOwnership(startx, starty, board, fill, pc, kar, minOwnership);
                System.out.println("corner points: (" + startx + ", " + starty + ") : " + Intersection.color2name(pc.stone) + " = " + pc.count);
                if (pc.count > 0) return pc;
            }
        }
        return null;
    }
}
