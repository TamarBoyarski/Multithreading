package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ex.Table;
import bguspl.set.ex.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

	/**
	 * The game environment object.
	 */
	private final Env env;

	/**
	 * Game entities.
	 */
	private final Table table;
	private final Player[] players;

	/**
	 * The list of card ids that are left in the dealer's deck.
	 */
	private final List<Integer> deck;

	/**
	 * True iff game should be terminated.
	 */
	private volatile boolean terminate;

	/**
	 * The time when the dealer needs to reshuffle the deck due to turn timeout.
	 */
	private long reshuffleTime = Long.MAX_VALUE;

	protected LinkedBlockingQueue<Player> submittedCards;
	private Player playerToCheck;
	protected int[] slotsArray;
	private boolean checked;
	protected boolean updating;
	protected LinkedList<Integer> freezeSlots;

	public Dealer(Env env, Table table, Player[] players) {
		this.env = env;
		this.table = table;
		this.players = players;
		deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
		slotsArray = null;
		submittedCards = new LinkedBlockingQueue<Player>();
		checked = false;
		updating = true;
		freezeSlots = new LinkedList<Integer>();
	}

	/**
	 * The dealer thread starts here (main loop for the dealer thread).
	 */
	@Override

	public void run() {
		env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
		for (int i = 0; i < players.length; i++) {
			Thread player = new Thread(players[i]);
			player.start();
		}
		while (!shouldFinish()) {
			synchronized (table) {
				placeCardsOnTable();
				for (int i = 0; i < players.length; i++) {
					players[i].clearActions();
					players[i].tokens.clear();
					if (submittedCards.contains(players[i]) && checked == false) {
						players[i].isOver = true;
						players[i].AddToPointOrPenality(players[i]);
					}
				}
				submittedCards.clear();
				
			}
			updateTimerDisplay(true);
			updating = false;
			freezeSlots.clear();
			timerLoop();
			updating = true;
			updateTimerDisplay(true);
			removeAllCardsFromTable();

		}
		announceWinners();
		env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
	}

	/**
	 * The inner loop of the dealer thread that runs as long as the countdown did
	 * not time out.
	 */

	private void timerLoop() {
		while (!terminate && System.currentTimeMillis() < reshuffleTime) {
			sleepUntilWokenOrTimeout();
			updateTimerDisplay(false);
			removeCardsFromTable();
			if (env.util.findSets(deck, 1).size() == 0)
				terminate();
			placeCardsOnTable();
			freezeSlots.clear();
		}
	}

	/**
	 * Called when the game should be terminated.
	 */
	public void terminate() {
		// TODO implement
		for (int i = players.length-1; i >= 0 ; i--) {
			players[i].terminate();
		}
		terminate = true;
	}

	/**
	 * Check if the game should be terminated or the game end conditions are met.
	 *
	 * @return true iff the game should be finished.
	 */
	private boolean shouldFinish() {
		return terminate || env.util.findSets(deck, 1).size() == 0;
	}

	/**
	 * Checks cards should be removed from the table and removes them.
	 */
	private void removeCardsFromTable() {
		if (slotsArray != null) {
			for (int i = 0; i < slotsArray.length; i++) {
				synchronized (table.tokensToSlot[slotsArray[i]]) {
					freezeSlots.add(slotsArray[i]);
					if (table.tokensToSlot[slotsArray[i]].size() > 0) {
						for (int j = table.tokensToSlot[slotsArray[i]].size() - 1; j >= 0; j--) {
							int playerIdToRemove = table.tokensToSlot[slotsArray[i]].get(j);
							for (int k = 0; k < players.length; k++) {
								if (players[k].getId() == playerIdToRemove) {
									players[k].removeTokenFromList(slotsArray[i]);
									table.removeToken(playerIdToRemove, slotsArray[i]);
									if (submittedCards.contains(players[k])) {
										players[k].isRemoved = true;
										submittedCards.remove(players[k]);
										players[k].AddToPointOrPenality(players[k]);
									}
								}
							}
						}
					}
					for (int j = 0; j < players.length; j++) {
						players[j].removeActionFromQueue(slotsArray[i]);
					}
					table.removeCard(slotsArray[i]);
				}
			}
			slotsArray = null;
		}
	}

	/**
	 * Check if any cards can be removed from the deck and placed on the table.
	 */
	private void placeCardsOnTable() {
		Collections.shuffle(deck);
		for (int i = 0; i < table.slotToCard.length; i++) {
			if (table.slotToCard[i] == null && !deck.isEmpty()) {
				table.placeCard(deck.remove(0), i);
			}
		}
	}

	/**
	 * Sleep for a fixed amount of time or until the thread is awakened for some
	 * purpose.
	 */
	private void sleepUntilWokenOrTimeout() {
		// TODO implement
		try {
			if (reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis) {
				playerToCheck = submittedCards.poll(1000, TimeUnit.MILLISECONDS);
			} else {
				playerToCheck = submittedCards.poll();
			}
			if (playerToCheck != null) {
				testIfLegal(playerToCheck);
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * Reset and/or update the countdown and the countdown display.
	 */
	private void updateTimerDisplay(boolean reset) {
		// TODO implement
		if (reset) {
			reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
			env.ui.setCountdown(env.config.turnTimeoutMillis, false);
		} else {
			long timeLeft = reshuffleTime - System.currentTimeMillis();
			env.ui.setCountdown(timeLeft, timeLeft < env.config.turnTimeoutWarningMillis);
		}
	}

	/**
	 * Returns all the cards from the table to the deck.
	 */

	private void removeAllCardsFromTable() {
		synchronized (table) {
			for(int i = 0; i < env.config.tableSize; i++) {
				env.ui.removeTokens();
				deck.add(table.slotToCard[i]);
				table.removeCard(i);
				freezeSlots.add(i);
				for(Player p : players){
					table.removeToken(p.getId(), i);
				}
			}
			for (int i = 0; i < table.tokensToSlot.length; i++) {
				table.tokensToSlot[i].clear();
			}

			Collections.shuffle(deck);
		}
	}

	/**
	 * Check who is/are the winner/s and displays them.
	 */
	private void announceWinners() {
		// TODO implement
		LinkedList<Player> winners = new LinkedList<Player>();
		int max = 0;
		for (int i = 0; i < players.length; i++) {
			if (players[i].score() >= max) {
				winners.add(players[i]);
				max = players[i].score();
				for (int j = winners.size() - 1; j >= 0; j--) {
					if (winners.get(j).score() < max) {
						winners.remove(j);
					}
				}
			}
		}
		int[] winnersArray = new int[winners.size()];
		for (int i = 0; i < winnersArray.length; i++) {
			winnersArray[i] = winners.get(i).getId();
		}
		env.ui.announceWinner(winnersArray);
	}

	public synchronized void addSetToTest(Player p) {
		try {
			submittedCards.put(p);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public synchronized void testIfLegal(Player p) {
		if (p.getTokensList().size() == env.config.featureSize) {
			checked = true;
			LinkedList<Integer> tokens = p.getTokensList();
			int[] cardsArray = new int[tokens.size()];
			p.clearActions();
			slotsArray = new int[tokens.size()];
			for (int i = 0; i < tokens.size(); i++) {
				slotsArray[i] = tokens.get(i);
				if (table.slotToCard[tokens.get(i)] != null) {
					cardsArray[i] = table.slotToCard[tokens.get(i)];
				} else
					cardsArray[i] = -1;
			}
			if (env.util.testSet(cardsArray) == true) {
				p.point = true;
				p.tokens.clear();
				for (int i = 0; i < slotsArray.length; i++) {
					//p.removeTokenFromList(slotsArray[i]);
					table.removeToken(p.getId(), slotsArray[i]);
				}
				//removeCardsFromTable();
				updateTimerDisplay(true);
			} else if (env.util.testSet(cardsArray) == false) {
				slotsArray = null;
				p.penalty = true;
			}
			p.AddToPointOrPenality(p);
		}
	}

}
