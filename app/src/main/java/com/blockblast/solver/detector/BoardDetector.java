package com.blockblast.solver.detector;

import android.content.Context;
import android.graphics.Bitmap;

public class BoardDetector {

    public static final int GRID   = 8;
    public static final int PIECES = 3;

    // ── Main board bounds (% of screen) ───────────────────────────────────
    public static final float BOARD_TOP_PCT    = 0.225f;
    public static final float BOARD_LEFT_PCT   = 0.055f;
    public static final float BOARD_RIGHT_PCT  = 0.944f;
    public static final float BOARD_BOTTOM_PCT = 0.665f;

    // ── Tray search band — wider than before to catch tall pieces ─────────
    public static final float TRAY_SEARCH_TOP_PCT    = 0.700f;
    public static final float TRAY_SEARCH_BOTTOM_PCT = 0.900f;

    // Keep these for OverlayView rendering (it uses the narrower visual band)
    public static final float TRAY_TOP_PCT    = 0.745f;
    public static final float TRAY_BOTTOM_PCT = 0.835f;

    // Horizontal slot centres (% of screen width)
    public static final float[] PIECE_CENTER_X = { 0.19f, 0.50f, 0.81f };

    // Fraction of screen width that one slot occupies (for column scan range)
    private static final float SLOT_HALF_WIDTH_PCT = 0.135f;

    // Luma threshold: pixels brighter than this are "filled"
    private static final int LUMA_THRESHOLD = 75;

    // ── Outputs ────────────────────────────────────────────────────────────
    public boolean[][] board  = new boolean[GRID][GRID];
    public boolean[][][] pieces = new boolean[PIECES][5][5];

    // Debug values consumed by OverlayView
    public int   debugTrayTop, debugTrayBottom;
    public int[] debugPieceX     = new int[PIECES];
    public int   debugCellSize;

    // ── Detect ────────────────────────────────────────────────────────────
    public void detect(Bitmap bmp, final Context context) {
        int W = bmp.getWidth();
        int H = bmp.getHeight();

        // 1. Main board
        int bLeft   = (int)(BOARD_LEFT_PCT   * W);
        int bRight  = (int)(BOARD_RIGHT_PCT  * W);
        int bTop    = (int)(BOARD_TOP_PCT    * H);
        int bBottom = (int)(BOARD_BOTTOM_PCT * H);
        int cellW   = (bRight  - bLeft)   / GRID;
        int cellH   = (bBottom - bTop)    / GRID;

        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                int px = bLeft + col * cellW + cellW / 2;
                int py = bTop  + row * cellH + cellH / 2;
                if (px < W && py < H) {
                    board[row][col] = luma(bmp.getPixel(px, py)) > 65;
                }
            }
        }

        // 2. Estimate tray cell size from actual pixel data
        int traySearchTop    = (int)(TRAY_SEARCH_TOP_PCT    * H);
        int traySearchBottom = (int)(TRAY_SEARCH_BOTTOM_PCT * H);
        int estimatedCell    = estimateTrayCellSize(bmp, W, H, traySearchTop, traySearchBottom);

        // Fallback: use board cell width scaled down (tray cells ≈ 55% of board cells)
        if (estimatedCell <= 0) estimatedCell = (int)(cellW * 0.55f);

        debugCellSize  = estimatedCell;
        debugTrayTop    = (int)(TRAY_TOP_PCT    * H);
        debugTrayBottom = (int)(TRAY_BOTTOM_PCT * H);

        for (int p = 0; p < PIECES; p++) {
            debugPieceX[p] = (int)(PIECE_CENTER_X[p] * W);
        }

        // 3. Scan each piece slot
        for (int p = 0; p < PIECES; p++) {
            int slotCX = (int)(PIECE_CENTER_X[p] * W);
            int slotLeft  = (int)((PIECE_CENTER_X[p] - SLOT_HALF_WIDTH_PCT) * W);
            int slotRight = (int)((PIECE_CENTER_X[p] + SLOT_HALF_WIDTH_PCT) * W);
            slotLeft  = Math.max(0, slotLeft);
            slotRight = Math.min(W - 1, slotRight);

            // Find the vertical centroid of lit pixels in this slot's column band
            int cY = findPieceVerticalCenter(bmp, slotLeft, slotRight,
                                             traySearchTop, traySearchBottom);
            if (cY < 0) {
                // Slot appears empty — fall back to mid-tray
                cY = (traySearchTop + traySearchBottom) / 2;
            }

            // Snap horizontal center to the brightest column within the slot
            int cX = findPieceHorizontalCenter(bmp, slotLeft, slotRight, cY, estimatedCell);
            if (cX < 0) cX = slotCX;

            debugPieceX[p] = cX;

            // Sample 5×5 grid centred on (cX, cY)
            for (int dr = -2; dr <= 2; dr++) {
                for (int dc = -2; dc <= 2; dc++) {
                    int px = cX + dc * estimatedCell;
                    int py = cY + dr * estimatedCell;
                    int r  = dr + 2;
                    int c  = dc + 2;
                    if (px >= 0 && px < W && py >= 0 && py < H) {
                        pieces[p][r][c] = luma(bmp.getPixel(px, py)) > LUMA_THRESHOLD;
                    } else {
                        pieces[p][r][c] = false;
                    }
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Luma (brightness) of an ARGB pixel, 0–255. */
    private static int luma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;
        return (int)(0.299 * r + 0.587 * g + 0.114 * b);
    }

    /**
     * Estimates the cell size used by tray pieces by scanning all three slots
     * for vertical runs of lit pixels and measuring the gap / run lengths.
     *
     * Strategy: collect all lit-pixel Y positions in the tray band, then
     * look for repeating gaps that correspond to cell boundaries.
     * Falls back to a simple bounding-box heuristic.
     */
    private int estimateTrayCellSize(Bitmap bmp, int W, int H,
                                     int trayTop, int trayBottom) {
        // Count lit pixels per row across all three slot bands
        int height = trayBottom - trayTop;
        if (height <= 0) return -1;
        int[] rowLitCount = new int[height];

        for (int p = 0; p < PIECES; p++) {
            int slotLeft  = (int)((PIECE_CENTER_X[p] - SLOT_HALF_WIDTH_PCT) * W);
            int slotRight = (int)((PIECE_CENTER_X[p] + SLOT_HALF_WIDTH_PCT) * W);
            slotLeft  = Math.max(0, slotLeft);
            slotRight = Math.min(W - 1, slotRight);

            // Sample every 3rd column for speed
            for (int x = slotLeft; x <= slotRight; x += 3) {
                for (int y = trayTop; y < trayBottom; y++) {
                    if (luma(bmp.getPixel(x, y)) > LUMA_THRESHOLD) {
                        rowLitCount[y - trayTop]++;
                    }
                }
            }
        }

        // Find first and last rows with significant lit pixels → piece bounding height
        int threshold = 3; // at least this many lit pixels to count as "occupied"
        int firstLit = -1, lastLit = -1;
        for (int i = 0; i < height; i++) {
            if (rowLitCount[i] >= threshold) {
                if (firstLit < 0) firstLit = i;
                lastLit = i;
            }
        }

        if (firstLit < 0 || lastLit <= firstLit) return -1;

        int pieceSpanH = lastLit - firstLit + 1;

        // Count internal dark-row gaps to determine how many cells tall the piece is
        // A gap is a consecutive run of sub-threshold rows inside the bounding box
        int cellRows = countCellRows(rowLitCount, firstLit, lastLit, threshold);
        if (cellRows <= 0) cellRows = 1;

        return pieceSpanH / cellRows;
    }

    /**
     * Counts how many cell rows make up the piece by detecting inter-cell gaps
     * (short dark runs) within the bounding rows [first..last].
     */
    private int countCellRows(int[] rowLitCount, int first, int last, int threshold) {
        boolean inGap = false;
        int cellCount = 1;
        int darkRun   = 0;

        for (int i = first; i <= last; i++) {
            if (rowLitCount[i] < threshold) {
                darkRun++;
                if (!inGap && darkRun >= 2) { // ≥2 dark rows = inter-cell gap
                    inGap = true;
                    cellCount++;
                }
            } else {
                darkRun = 0;
                inGap   = false;
            }
        }
        return cellCount;
    }

    /**
     * Returns the Y coordinate of the vertical centroid of lit pixels
     * within the given column band and row band.  Returns -1 if no lit pixels.
     */
    private int findPieceVerticalCenter(Bitmap bmp, int xLeft, int xRight,
                                        int yTop, int yBottom) {
        long sumY = 0, count = 0;
        for (int x = xLeft; x <= xRight; x += 3) {
            for (int y = yTop; y < yBottom; y++) {
                if (luma(bmp.getPixel(x, y)) > LUMA_THRESHOLD) {
                    sumY += y;
                    count++;
                }
            }
        }
        return count > 10 ? (int)(sumY / count) : -1;
    }

    /**
     * Returns the X coordinate of the horizontal centroid of lit pixels
     * in a horizontal band centred on cY ± half a cell.
     * Returns -1 if no lit pixels.
     */
    private int findPieceHorizontalCenter(Bitmap bmp, int xLeft, int xRight,
                                          int cY, int cellSize) {
        int bandTop    = Math.max(0, cY - cellSize / 2);
        int bandBottom = Math.min(bmp.getHeight() - 1, cY + cellSize / 2);
        long sumX = 0, count = 0;
        for (int x = xLeft; x <= xRight; x++) {
            for (int y = bandTop; y <= bandBottom; y += 2) {
                if (luma(bmp.getPixel(x, y)) > LUMA_THRESHOLD) {
                    sumX += x;
                    count++;
                }
            }
        }
        return count > 5 ? (int)(sumX / count) : -1;
    }
}
