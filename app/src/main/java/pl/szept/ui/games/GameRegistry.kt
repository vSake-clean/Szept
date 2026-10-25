package pl.szept.ui.games

import androidx.compose.runtime.Composable

/** Gra na pełnym ekranie (sam albo wyzwanie z rozmowy). */
typealias SoloGame = @Composable (GameEnv) -> Unit

private fun MutableMap<String, SoloGame>.g(id: String, game: SoloGame) { put(id, game) }

/** Wszystkie gry, w które można zagrać na pełnym ekranie (wbudowane; Python uruchamia osobna aktywność). */
val SOLO_GAMES: Map<String, SoloGame> = buildMap {
    // zręcznościowe
    g("snake") { SnakeGame(it) }
    g("flappy") { FlappyGame(it) }
    g("tetris") { TetrisGame(it) }
    g("breakout") { BreakoutGame(it) }
    g("pong") { PongGame(it) }
    g("dodge") { DodgeGame(it) }
    g("shooter") { ShooterGame(it) }
    g("runner") { RunnerGame(it) }
    g("jumper") { JumperGame(it) }
    g("stack") { StackGame(it) }
    g("balloons") { BalloonsGame(it) }
    g("mole") { MoleGame(it) }
    g("aim") { AimGame(it) }
    g("tapper") { TapperGame(it) }
    g("eggs") { EggsGame(it) }
    // logiczne
    g("2048") { Game2048(it) }
    g("mines") { MinesGame(it) }
    g("sudoku") { SudokuGame(it) }
    g("fifteen") { FifteenGame(it) }
    g("lights") { LightsGame(it) }
    g("flood") { FloodGame(it) }
    g("maze") { MazeGame(it) }
    g("hanoi") { HanoiGame(it) }
    g("peg") { PegGame(it) }
    g("sokoban") { SokobanGame(it) }
    g("mastermind") { MastermindGame(it) }
    g("match3") { Match3Game(it) }
    g("lines") { LinesGame(it) }
    // pamięć i refleks
    g("memory") { MemoryGame(it) }
    g("simon") { SimonGame(it) }
    g("digits") { DigitsGame(it) }
    g("chimp") { ChimpGame(it) }
    g("reflex") { ReflexGame(it) }
    g("schulte") { SchulteGame(it) }
    g("shade") { ShadeGame(it) }
    g("stopwatch") { StopwatchGame(it) }
    g("stroop") { StroopGame(it) }
    g("math") { MathGame(it) }
    // słowa i quizy
    g("wordle") { WordleGame(it) }
    g("hangsolo") { HangSoloGame(it) }
    g("anagram") { AnagramGame(it) }
    g("typing") { TypingGame(it) }
    g("quiz") { QuizGame(it) }
    g("truefalse") { TrueFalseGame(it) }
    // karty i kości
    g("blackjack") { BlackjackGame(it) }
    g("higherlower") { HigherLowerGame(it) }
    g("slots") { SlotsGame(it) }
    g("yahtzee") { YahtzeeGame(it) }
    g("guessnum") { GuessNumGame(it) }
    // planszowe — z komputerem
    for (id in listOf("ttt", "c4", "gomoku", "reversi", "checkers", "chess", "dots", "nim", "mancala", "morris", "battleship")) g(id) { DuelVsAi(id, it) }
    g("rps") { RpsVsAi(it) }
}
