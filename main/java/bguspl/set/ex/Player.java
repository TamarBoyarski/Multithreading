package bguspl.set.ex;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

	/**
	 * The game environment object.
	 */
	private final Env env;

	/**
	 * Game entities.
	 */
	private final Table table;

	/**
	 * The id of the player (starting from 0).
	 */
	public final int id;

	/**
	 * The thread representing the current player.
	 */
	private Thread playerThread;

	/**
	 * The thread of the AI (computer) player (an additional thread used to generate
	 * key presses).
	 */
	private Thread aiThread;

	/**
	 * True iff the player is human (not a computer player).
	 */
	private final boolean human;

	/**
	 * True iff game should be terminated.
	 */
	private volatile boolean terminate;

	/**
	 * The current score of the player.
	 */
	private int score;

	private Dealer dealer;

	private boolean isLegal;
	protected boolean isRemoved;

	private LinkedBlockingQueue<Integer> actions;

	private LinkedBlockingQueue<Player> pointOrPenality;

	protected LinkedList<Integer> tokens;
	
	protected boolean point;
	
	protected boolean penalty;
	
	protected boolean isOver;
	
	

	// private static final Object[] slotLocks = new Object[table.slotToCard.size];

	/**
	 * The class constructor.
	 *
	 * @param env    - the environment object.
	 * @param dealer - the dealer object.
	 * @param table  - the table object.
	 * @param id     - the id of the player.
	 * @param human  - true iff the player is a human player (i.e. input is provided
	 *               manually, via the keyboard).
	 */
	public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
		this.env = env;
		this.table = table;
		this.id = id;
		this.human = human;
		this.dealer = dealer;
		this.tokens = new LinkedList<Integer>();
		this.actions = new LinkedBlockingQueue<Integer>();
		this.pointOrPenality = new LinkedBlockingQueue<Player>();
		this.isRemoved = false;
		this.isLegal = false;
		this.isOver = false;
		
	}

	/**
	 * The main player thread of each player starts here (main loop for the player
	 * thread).
	 */
	@Override
	public void run() {
		playerThread = Thread.currentThread();
		env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
		if (!human)
			createArtificialIntelligence();

		while (!terminate && !Thread.currentThread().isInterrupted()) {
			// TODO implement main player loop
			if (actions != null) {
				try {
					int slot = actions.take();
					addOrRemove(slot);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
				}

			}
		}
		if (!human)
			try {
				aiThread.join();
			} catch (InterruptedException ignored) {
			}
		env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
	}

	/**
	 * Creates an additional thread for an AI (computer) player. The main loop of
	 * this thread repeatedly generates key presses. If the queue of key presses is
	 * full, the thread waits until it is not full.
	 */
	private void createArtificialIntelligence() {
		// note: this is a very, very smart AI (!)
		aiThread = new Thread(() -> {
			env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
			while (!terminate && !Thread.currentThread().isInterrupted()) {
				// TODO implement player key press simulator
				Random random = new Random();
				int slot = random.nextInt(env.config.tableSize);
				keyPressed(slot);
				try {
					Thread.sleep(2);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
				}
			}
			env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
		}, "computer-" + id);
		aiThread.start();
	}

	/**
	 * Called when the game should be terminated.
	 */
	
	public void terminate() {//change?
	    terminate = true;
	    playerThread.interrupt();
	    try {
	        playerThread.join();
	    } catch (InterruptedException e) {
	        e.printStackTrace();
	    }
	    env.logger.info("Player " + id + " terminated.");
	}

	/**
	 * This method is called when a key is pressed.
	 *
	 * @param slot - the slot corresponding to the key pressed.
	 */
	public void keyPressed(int slot) {
		if (actions != null) {
			if (!point&&!penalty&&!dealer.freezeSlots.contains((Integer)slot) && !dealer.updating && table.slotToCard[slot] != null && actions.size() < env.config.featureSize) {
				try {
					actions.put(slot);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} // try and catch??
			}
		}
	}
	
	public void addOrRemove(int slot) {
		if (tokens.contains(slot) && !dealer.freezeSlots.contains((Integer)slot) && !dealer.updating &&!point&&!penalty) {
			table.removeToken(id, slot);
			tokens.remove(tokens.indexOf(slot));
		} 
		else if(tokens.size() < env.config.featureSize && table.slotToCard[slot] != null && !dealer.freezeSlots.contains((Integer)slot) && !dealer.updating &&!point&&!penalty) {
			table.placeToken(id, slot);
			tokens.addLast(slot);
			if (tokens.size() == env.config.featureSize) {
				try {
					dealer.submittedCards.put(this);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				try {
					pointOrPenality.take();
					if (isRemoved) {
						isRemoved = false;
					
						return;
					}
					else if (isOver) {
						isOver = false;
					
						return;
					}
					else if (point == true && penalty == false) {
						point();
						point=false;
					} else if (point == false && penalty == true) {
						penalty();
						penalty=false;
					}
				} catch (InterruptedException e) {
				}

			}
		}
		}
		// TODO implement


	/**
	 * Award a point to a player and perform other related actions.
	 *
	 * @post - the player's score is increased by 1.
	 * @post - the player's score is updated in the ui.
	 */
	public void point() {
		// TODO implement

		int ignored = table.countCards(); // this part is just for demonstration in the unit tests
		env.ui.setScore(id, ++score);
		long sleepTill = System.currentTimeMillis() + env.config.pointFreezeMillis;
		long timeLeft = env.config.pointFreezeMillis;
		while (timeLeft > 0) {
			env.ui.setFreeze(id, timeLeft);
			try {
				if (timeLeft < 500)
					Thread.sleep(timeLeft);
				else
					Thread.sleep(500);
				timeLeft = sleepTill - System.currentTimeMillis();
			} catch (Exception e) {
			}
		}
		env.ui.setFreeze(id, 0);
	}

	/**
	 * Penalize a player and perform other related actions.
	 */
	public void penalty() {
		// TODO implement
		long sleepTill = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
		long timeLeft = env.config.penaltyFreezeMillis;
		while (timeLeft > 0) {
			env.ui.setFreeze(id, timeLeft);
			try {
				if (timeLeft < 500)
					Thread.sleep(timeLeft);
				else
					Thread.sleep(500);
				timeLeft = sleepTill - System.currentTimeMillis();
			} catch (Exception e) {
			}
		}
		env.ui.setFreeze(id, 0);

	}

	public int score() {
		return score;
	}

	public LinkedList<Integer> getTokensList() {
		return tokens;
	}

	public void removeTokenFromList(Integer slot) {
		tokens.remove(slot);
		
		
	}

	public void setBooleanIsLegal(boolean isLegal) {
		this.isLegal = isLegal;
	}

	public void AddToPointOrPenality(Player p) {
		try {
			pointOrPenality.put(p);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public int getId() {
		return id;
	}

	public void removeActionFromQueue(int slot) {
		actions.remove(slot);

	}

	public void clearActions() {
		actions.clear();
	}
}
