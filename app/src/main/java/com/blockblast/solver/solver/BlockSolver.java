package com.blockblast.solver.solver;

import com.blockblast.solver.detector.BoardDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy solver for Block Blast.
 *
 * For each of the 3 available pieces, tries every valid (row, col) placement
 * on the 8×8 board and scores it.  Returns the best placement for each piece
 * (independent; not sequential — good enough for a helper overlay).
 *
 * Score function penalises:
 *   - Holes (empty cells with a filled cell above them)
 *   - Max occupied row (board height)
 *   - Remaining empty cells after lines cleared
 * and rewards:
 *   - Lines (rows + cols) cleared
 */
public class BlockSolver {

    public static final int GRID = BoardDetector.GRID;

    public static class Placement {
        public int piece;   // 0-2
        public int row;     // top-left row on board
        public int col;     // top-left col on board
        public double score;
        public boolean[][] shape; // 5×5 canonical shape

        public Placement(int piece, int row, int col, double score, boolean[][] shape) {
            this.piece = piece; this.row = row; this.col = col;
            this.score = score; this.shape = shape;
        }
    }

    /**
     * @param board   8×8 current board state
     * @param pieces  3 × 5×5 piece shapes
     * @return best Placement for each piece (null if piece has no valid moves)
     */
    public static Placement[] solve(boolean[][] board, boolean[][][] pieces) {
        Placement[] best = new Placement[3];

        for (int p = 0; p < 3; p++) {
            boolean[][] shape = normalise(pieces[p]);
            if (isEmpty(shape)) continue; // piece slot empty

            double bestScore = Double.NEGATIVE_INFINITY;

            for (int r = 0; r <= GRID - 1; r++) {
                for (int c = 0; c <= GRID - 1; c++) {
                    if (!canPlace(board, shape, r, c)) continue;

                    boolean[][] next = place(board, shape, r, c);
                    next = clearLines(next);
                    double sc = score(next, board, shape, r, c);

                    if (sc > bestScore) {
                        bestScore = sc;
                        best[p]   = new Placement(p, r, c, sc, shape);
                    }
                }
            }
        }
        return best;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Strip empty border rows/cols so shape is flush top-left. */
    private static boolean[][] normalise(boolean[][] raw) {
        // find bounding box
        int minR = 5, maxR = -1, minC = 5, maxC = -1;
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++)
                if (raw[r][c]) {
                    if (r < minR) minR = r; if (r > maxR) maxR = r;
                    if (c < minC) minC = c; if (c > maxC) maxC = c;
                }
        if (maxR < 0) return new boolean[5][5]; // empty
        boolean[][] out = new boolean[5][5];
        for (int r = minR; r <= maxR; r++)
            for (int c = minC; c <= maxC; c++)
                out[r - minR][c - minC] = raw[r][c];
        return out;
    }

    private static boolean isEmpty(boolean[][] s) {
        for (boolean[] row : s) for (boolean b : row) if (b) return false;
        return true;
    }

    private static boolean canPlace(boolean[][] board, boolean[][] shape, int tr, int tc) {
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++) {
                if (!shape[r][c]) continue;
                int br = tr + r, bc = tc + c;
                if (br >= GRID || bc >= GRID) return false;
                if (board[br][bc]) return false;
            }
        return true;
    }

    private static boolean[][] place(boolean[][] board, boolean[][] shape, int tr, int tc) {
        boolean[][] next = copy(board);
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++)
                if (shape[r][c]) next[tr + r][tc + c] = true;
        return next;
    }

    private static boolean[][] clearLines(boolean[][] b) {
        boolean[][] next = copy(b);
        // Clear full rows
        for (int r = 0; r < GRID; r++) {
            boolean full = true;
            for (int c = 0; c < GRID; c++) if (!next[r][c]) { full = false; break; }
            if (full) for (int c = 0; c < GRID; c++) next[r][c] = false;
        }
        // Clear full cols
        for (int c = 0; c < GRID; c++) {
            boolean full = true;
            for (int r = 0; r < GRID; r++) if (!next[r][c]) { full = false; break; }
            if (full) for (int r = 0; r < GRID; r++) next[r][c] = false;
        }
        return next;
    }

    private static double score(boolean[][] after, boolean[][] before,
                                boolean[][] shape, int tr, int tc) {
        // Count lines cleared
        int linesBefore = countFullLines(before);
        // Temporarily place then clear
        boolean[][] placed = place(before, shape, tr, tc);
        int linesAfter  = countFullLines(placed);
        int cleared     = linesAfter - linesBefore; // rows+cols newly cleared

        int holes   = countHoles(after);
        int height  = maxHeight(after);
        int filled  = countFilled(after);

        return cleared * 10.0
                - holes   * 3.0
                - height  * 0.5
                - filled  * 0.1;
    }

    private static int countFullLines(boolean[][] b) {
        int n = 0;
        for (int r = 0; r < GRID; r++) {
            boolean full = true;
            for (int c = 0; c < GRID; c++) if (!b[r][c]) { full = false; break; }
            if (full) n++;
        }
        for (int c = 0; c < GRID; c++) {
            boolean full = true;
            for (int r = 0; r < GRID; r++) if (!b[r][c]) { full = false; break; }
            if (full) n++;
        }
        return n;
    }

    private static int countHoles(boolean[][] b) {
        int holes = 0;
        for (int c = 0; c < GRID; c++) {
            boolean seenFilled = false;
            for (int r = 0; r < GRID; r++) {
                if (b[r][c]) seenFilled = true;
                else if (seenFilled) holes++;
            }
        }
        return holes;
    }

    private static int maxHeight(boolean[][] b) {
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++)
                if (b[r][c]) return GRID - r;
        return 0;
    }

    private static int countFilled(boolean[][] b) {
        int n = 0;
        for (boolean[] row : b) for (boolean v : row) if (v) n++;
        return n;
    }

    private static boolean[][] copy(boolean[][] src) {
        boolean[][] out = new boolean[GRID][GRID];
        for (int r = 0; r < GRID; r++) out[r] = src[r].clone();
        return out;
    }
}
