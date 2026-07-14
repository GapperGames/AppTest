package com.poolsight.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Hand-computed cases for the aiming engine, on a 1000 × 2000 mm pool table
 * (ball radius r = 28.575 mm, so 2r = 57.15 mm).
 *
 * Layout reminder: origin bottom-left; MIDDLE_LEFT pocket at (0, 1000),
 * MIDDLE_RIGHT at (1000, 1000).
 */
class ShotSolverTest {

    private val table = Table(1000.0, 2000.0, GameType.POOL)
    private val solver = ShotSolver(table)
    private val r = table.ballRadius // 28.575

    private val middleLeft = table.pockets.first { it.id == PocketId.MIDDLE_LEFT }
    private val middleRight = table.pockets.first { it.id == PocketId.MIDDLE_RIGHT }

    // ---- direct shots ------------------------------------------------------

    @Test
    fun `dead straight pot - ghost sits one ball-diameter behind the object ball`() {
        // Cue (900,1000) → object (500,1000) → pocket (0,1000): all on one line.
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))

        val result = solver.solveDirect(cue, obj, middleLeft)
        val shot = assertIs<ShotResult.Solution>(result)

        // G = O + 2r·unit(O−P) = (500 + 57.15, 1000)
        assertEquals(557.15, shot.ghost.x, 1e-9)
        assertEquals(1000.0, shot.ghost.y, 1e-9)

        // Contact point one radius from the centre, facing away from the pocket.
        assertEquals(528.575, shot.contactPoint.x, 1e-9)
        assertEquals(1000.0, shot.contactPoint.y, 1e-9)

        // Straight shot: no cut, full-ball contact, easy.
        assertEquals(0.0, shot.cutAngleDegrees, 1e-9)
        assertEquals(1.0, shot.cutFraction, 1e-9)
        assertEquals(Difficulty.EASY, shot.difficulty)
        assertEquals(listOf(obj.center, middleLeft.center), shot.objectPath)
        assertTrue(!shot.isBank)

        // Aim direction points from cue toward the ghost (negative x).
        assertEquals(-1.0, shot.aimDirection.x, 1e-9)
        assertEquals(0.0, shot.aimDirection.y, 1e-9)
    }

    @Test
    fun `45 degree cut is reported as 45 degrees with half-root-two fraction`() {
        val obj = Ball("8", Vec2(500.0, 1000.0))
        // Ghost is at (557.15, 1000). Put the cue diagonally below-right of it
        // so the aim line C→G runs at 45° to the object ball's line to the pocket.
        val cue = Ball("cue", Vec2(557.15 + 300.0, 1000.0 - 300.0))

        val shot = assertIs<ShotResult.Solution>(solver.solveDirect(cue, obj, middleLeft))
        assertEquals(45.0, shot.cutAngleDegrees, 1e-6)
        assertEquals(Math.cos(Math.toRadians(45.0)), shot.cutFraction, 1e-9)
    }

    @Test
    fun `object ball frozen near the rail cannot be potted through the rail`() {
        // Object 30mm off the left rail, pocket on the right: the ghost would
        // sit outside the playing surface behind the object ball.
        val cue = Ball("cue", Vec2(500.0, 500.0))
        val obj = Ball("8", Vec2(30.0, 1000.0))

        val result = solver.solveDirect(cue, obj, middleRight)
        val impossible = assertIs<ShotResult.Impossible>(result)
        assertEquals(ShotProblem.GHOST_UNREACHABLE, impossible.problem)
    }

    @Test
    fun `cue ball in front of the object ball cannot cut it backwards`() {
        // Cue sits between object and pocket on the same line: aiming at the
        // ghost would push the object AWAY from the pocket (180° cut).
        val cue = Ball("cue", Vec2(400.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))

        val impossible = assertIs<ShotResult.Impossible>(solver.solveDirect(cue, obj, middleLeft))
        assertEquals(ShotProblem.CUT_TOO_THIN, impossible.problem)
    }

    // ---- obstruction checks ------------------------------------------------

    @Test
    fun `ball sitting on the cue path blocks the shot`() {
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))
        // 30mm off the cue→ghost line (x∈[557.15, 900] at y=1000): 30 < 2r → blocks.
        val blocker = Ball("3", Vec2(700.0, 1030.0))

        val impossible = assertIs<ShotResult.Impossible>(
            solver.solveDirect(cue, obj, middleLeft, otherBalls = listOf(blocker)),
        )
        assertEquals(ShotProblem.CUE_PATH_BLOCKED, impossible.problem)
        assertEquals("3", impossible.blockingBallId)
    }

    @Test
    fun `ball sitting on the object path blocks the shot`() {
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))
        // 40mm off the object→pocket line (x∈[0,500] at y=1000): 40 < 2r → blocks.
        val blocker = Ball("5", Vec2(250.0, 1040.0))

        val impossible = assertIs<ShotResult.Impossible>(
            solver.solveDirect(cue, obj, middleLeft, otherBalls = listOf(blocker)),
        )
        assertEquals(ShotProblem.OBJECT_PATH_BLOCKED, impossible.problem)
        assertEquals("5", impossible.blockingBallId)
    }

    @Test
    fun `ball clearly off both paths does not block`() {
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))
        // 100mm off the line: more than 2r = 57.15 → clear.
        val bystander = Ball("7", Vec2(700.0, 1100.0))

        assertIs<ShotResult.Solution>(
            solver.solveDirect(cue, obj, middleLeft, otherBalls = listOf(bystander)),
        )
    }

    // ---- bank shots ---------------------------------------------------------

    @Test
    fun `one-cushion bank obeys the reflection law at the mirror line`() {
        // Bank the object ball off the right cushion into the middle-left pocket.
        val cue = Ball("cue", Vec2(300.0, 400.0))
        val obj = Ball("8", Vec2(500.0, 800.0))
        val rightLower = table.cushions.first { it.id == CushionId.RIGHT_LOWER }

        val shot = assertIs<ShotResult.Solution>(
            solver.solveBank(cue, obj, middleLeft, rightLower),
        )

        assertTrue(shot.isBank)
        assertEquals(CushionId.RIGHT_LOWER, shot.bankCushion)
        assertEquals(3, shot.objectPath.size)

        val (start, bounce, end) = shot.objectPath
        assertEquals(obj.center, start)
        assertEquals(middleLeft.center, end)

        // The ball's centre rebounds one radius short of the cushion nose:
        // the mirror line for the right cushion (x=1000) is x = 1000 − r.
        assertEquals(1000.0 - r, bounce.x, 1e-9)

        // Reflection law: angle in = angle out. For a vertical mirror the
        // incoming and outgoing slopes (dy/dx) are equal and opposite.
        val incoming = bounce - start
        val outgoing = end - bounce
        val slopeIn = incoming.y / incoming.x
        val slopeOut = outgoing.y / outgoing.x
        assertEquals(slopeIn, -slopeOut, 1e-9)
        assertTrue(incoming.x > 0 && outgoing.x < 0) // in toward the rail, out away
    }

    @Test
    fun `bank into a pocket on the same rail is rejected`() {
        // Object near the left rail, asked to bank off that same rail into the
        // pocket mounted on it: geometrically nonsensical.
        val cue = Ball("cue", Vec2(500.0, 400.0))
        val obj = Ball("8", Vec2(30.0, 500.0))
        val leftLower = table.cushions.first { it.id == CushionId.LEFT_LOWER }

        val result = solver.solveBank(cue, obj, middleLeft, leftLower)
        val impossible = assertIs<ShotResult.Impossible>(result)
        assertTrue(
            impossible.problem == ShotProblem.BANK_WRONG_SIDE ||
                impossible.problem == ShotProblem.BANK_NO_BOUNCE,
        )
    }

    @Test
    fun `bank second leg blocked by a ball is rejected`() {
        val cue = Ball("cue", Vec2(300.0, 400.0))
        val obj = Ball("8", Vec2(500.0, 800.0))
        val rightLower = table.cushions.first { it.id == CushionId.RIGHT_LOWER }

        // First solve unobstructed to find the bounce point, then park a
        // blocker on the bounce→pocket leg and expect rejection.
        val clear = assertIs<ShotResult.Solution>(solver.solveBank(cue, obj, middleLeft, rightLower))
        val bounce = clear.objectPath[1]
        val midSecondLeg = (bounce + middleLeft.center) * 0.5
        val blocker = Ball("9", midSecondLeg)

        val blocked = solver.solveBank(cue, obj, middleLeft, rightLower, otherBalls = listOf(blocker))
        val impossible = assertIs<ShotResult.Impossible>(blocked)
        assertEquals(ShotProblem.OBJECT_PATH_BLOCKED, impossible.problem)
        assertEquals("9", impossible.blockingBallId)
    }

    // ---- solveAll -----------------------------------------------------------

    @Test
    fun `solveAll returns the easy direct pot first`() {
        val cue = Ball("cue", Vec2(900.0, 1000.0))
        val obj = Ball("8", Vec2(500.0, 1000.0))

        val solutions = solver.solveAll(cue, obj, middleLeft)
        assertTrue(solutions.isNotEmpty())
        val best = solutions.first()
        assertTrue(!best.isBank)
        assertEquals(Difficulty.EASY, best.difficulty)
        // Any banks found must rank after the direct shot.
        assertTrue(solutions.drop(1).all { it.isBank || it.cutAngleDegrees >= best.cutAngleDegrees })
    }

    @Test
    fun `difficulty increases with cut angle`() {
        val obj = Ball("8", Vec2(500.0, 1000.0))
        // Same ghost, increasingly thin cuts by moving the cue around it.
        fun cutAt(angleDeg: Double): ShotResult.Solution {
            val rad = Math.toRadians(angleDeg)
            val ghost = Vec2(557.15, 1000.0)
            val cue = Ball("cue", ghost + Vec2(Math.cos(rad), Math.sin(rad)) * 400.0)
            return assertIs<ShotResult.Solution>(solver.solveDirect(cue, obj, middleLeft))
        }
        val straight = cutAt(0.0)
        val thin = cutAt(70.0)
        assertEquals(0.0, straight.cutAngleDegrees, 1e-6)
        assertEquals(70.0, thin.cutAngleDegrees, 1e-6)
        assertTrue(thin.difficulty.ordinal > straight.difficulty.ordinal)
        assertTrue(abs(thin.cutFraction - Math.cos(Math.toRadians(70.0))) < 1e-9)
    }
}
