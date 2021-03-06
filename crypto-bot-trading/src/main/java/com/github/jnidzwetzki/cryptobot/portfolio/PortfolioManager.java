/*******************************************************************************
 *
 *    Copyright (C) 2015-2018 Jan Kristof Nidzwetzki
 *  
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License. 
 *    
 *******************************************************************************/
package com.github.jnidzwetzki.cryptobot.portfolio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bboxdb.commons.MathUtil;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jnidzwetzki.bitfinex.v2.BitfinexApiBroker;
import com.github.jnidzwetzki.bitfinex.v2.BitfinexOrderBuilder;
import com.github.jnidzwetzki.bitfinex.v2.entity.APIException;
import com.github.jnidzwetzki.bitfinex.v2.entity.BitfinexCurrencyPair;
import com.github.jnidzwetzki.bitfinex.v2.entity.BitfinexOrder;
import com.github.jnidzwetzki.bitfinex.v2.entity.BitfinexOrderType;
import com.github.jnidzwetzki.bitfinex.v2.entity.ExchangeOrder;
import com.github.jnidzwetzki.bitfinex.v2.entity.ExchangeOrderState;
import com.github.jnidzwetzki.bitfinex.v2.entity.Wallet;
import com.github.jnidzwetzki.bitfinex.v2.manager.OrderManager;
import com.github.jnidzwetzki.cryptobot.CurrencyEntry;
import com.github.jnidzwetzki.cryptobot.PortfolioOrderManager;
import com.github.jnidzwetzki.cryptobot.util.HibernateUtil;
import com.github.jnidzwetzki.cryptobot.util.MathHelper;
import com.google.common.annotations.VisibleForTesting;

public abstract class PortfolioManager {

	/**
	 * The bitfinex api broker
	 */
	protected BitfinexApiBroker bitfinexApiBroker;
	
	/**
	 * The order manager
	 */
	protected final OrderManager orderManager;

	/**
	 * The threshold for invested / not invested
	 */
	private static final double INVESTED_THRESHOLD = 0.002;

	/**
	 * Maximum loss per position 
	 */
	private final BigDecimal maxLossPerPosition;
	
	/**
	 * The maximum position size
	 */
	private static final BigDecimal MAX_SINGLE_POSITION_SIZE = new BigDecimal(0.5);
	
	/**
	 * Simulate or real trading
	 */
	public final static boolean SIMULATION = false;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(PortfolioManager.class);

	public PortfolioManager(BitfinexApiBroker bitfinexApiBroker, final double maxLossPerPosition) {
		this.bitfinexApiBroker = bitfinexApiBroker;
		
		this.orderManager = bitfinexApiBroker.getOrderManager();
		this.maxLossPerPosition = new BigDecimal(maxLossPerPosition);
		
		// Init to store orders in DB
		new PortfolioOrderManager(bitfinexApiBroker);
	}
	
	public void syncOrders(final Map<BitfinexCurrencyPair, CurrencyEntry> entries, 
			final Map<BitfinexCurrencyPair, Double> exits) throws InterruptedException, APIException {
				
		placeEntryOrders(entries);
		placeExitOrders(exits);
		
		updatePortfolioValue();
	}

	/**
	 * Write the USD portfolio value to DB
	 * @throws APIException
	 */
	private void updatePortfolioValue() throws APIException {
		logger.debug("Updating portfolio value");
		final BigDecimal portfolioValueUSD = getTotalPortfolioValueInUSD();
		final PortfolioValue portfolioValue = new PortfolioValue();
		portfolioValue.setApikey(bitfinexApiBroker.getApiKey());
		portfolioValue.setUsdValue(portfolioValueUSD);
		
		final SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
		
		try(final Session session = sessionFactory.openSession()) {
			session.beginTransaction();
			session.save(portfolioValue);
			session.getTransaction().commit();
		}
	}

	/**
	 * Place the entry orders for the market
	 * @param currencyPair
	 * @throws APIException 
	 */
	private void placeEntryOrders(final Map<BitfinexCurrencyPair, CurrencyEntry> entries) 
			throws InterruptedException, APIException {
		
		logger.info("Processing entry orders {}", entries);
		
		// Cancel old open entry orders
		cancelRemovedEntryOrders(entries);
		
		// Calculate the position sizes
		calculatePositionSizes(entries);

		// Place the new entry orders
		placeNewEntryOrders(entries);
	}

	/**
	 * Calculate the position sizes
	 * @param entries
	 * @throws APIException
	 */
	@VisibleForTesting
	public void calculatePositionSizes(final Map<BitfinexCurrencyPair, CurrencyEntry> entries) throws APIException {
		final Wallet wallet = getWalletForCurrency("USD");
		
		// Wallet could be empty
		if(wallet == null) {
			throw new APIException("Unable to find USD wallet");
		}
		
		final BigDecimal capitalAvailable = getAvailablePortfolioValueInUSD().multiply(getInvestmentRate());
		BigDecimal capitalNeeded = new BigDecimal(0);
		
		for(final BitfinexCurrencyPair currency : entries.keySet()) {
			final CurrencyEntry entry = entries.get(currency);
			final double positionSize = calculatePositionSize(entry);
			entry.setPositionSize(positionSize);
			capitalNeeded = capitalNeeded.add(new BigDecimal(positionSize * entry.getEntryPrice()));
		}
		
		// Need the n% risk per position more than the available capital
		if(capitalNeeded.doubleValue() > capitalAvailable.doubleValue()) {
			
			final double investmentCorrectionFactor = capitalAvailable.doubleValue() / capitalNeeded.doubleValue();

			logger.info("Needed capital {}, available capital {} ({})", capitalNeeded, 
					capitalAvailable, investmentCorrectionFactor);
			
			capitalNeeded = new BigDecimal(0);
			for(final BitfinexCurrencyPair currency : entries.keySet()) {
				final CurrencyEntry entry = entries.get(currency);
				final double newPositionSize = roundPositionSize(entry.getPositionSize() * investmentCorrectionFactor);
				entry.setPositionSize(newPositionSize);
				capitalNeeded = capitalNeeded.add(new BigDecimal(newPositionSize * entry.getEntryPrice()));
			}			
		}
	}

	/** 
	 * Has the entry order changed?
	 */
	private boolean hasEntryOrderChanged(final ExchangeOrder order, 
			final double entryPrice, final double positionSize) {
		
		final double orderPrice = order.getPrice().doubleValue();
		// Compare the ordersize (delta 0.5%)
		final boolean orderSizeEquals = MathHelper.almostEquals(orderPrice, entryPrice, 500);
		
		if(! orderSizeEquals) {
			return true;
		}
		
		if(orderPrice > entryPrice) {
			return true;
		}
		
		return false;
	}
	
	/**
	 * Place the new entry orders
	 * @param entries
	 * @throws APIException 
	 * @throws InterruptedException 
	 */
	private void placeNewEntryOrders(final Map<BitfinexCurrencyPair, CurrencyEntry> entries) 
			throws APIException, InterruptedException  {
		
		// Check current limits and position sizes
		for(final BitfinexCurrencyPair currency : entries.keySet()) {
			final ExchangeOrder order = getOpenOrderForSymbol(currency.toBitfinexString());
			
			final CurrencyEntry entry = entries.get(currency);
			final double entryPrice = entry.getEntryPrice();
			final double positionSize = entry.getPositionSize();

			if(positionSize < currency.getMinimumOrderSize()) {
				logger.info("Not placing order for {}, position size is too small {}", currency, positionSize);
				continue;
			}
			
			// Old order present
			if(order != null) {
				if(hasEntryOrderChanged(order, entryPrice, positionSize)) {
					logger.info("Entry order for {}, values changed (amount: {} / {}} (price: {} / {})", 
							currency, order.getAmount(), positionSize, order.getPrice(), entryPrice);	
					
					cancelOrder(order);
				} else {
					logger.info("Not placing a new order for {}, old order still active", currency);
					continue;
				}
			}
			
			final BitfinexOrder newOrder = BitfinexOrderBuilder
					.create(currency, getOrderType(), positionSize)
					.withPrice(entryPrice)
					.setPostOnly()
					.build();
	
			placeOrder(newOrder);	
		}
	}

	/**
	 *  Place an order and catch exceptions
	 * @throws InterruptedException 
	 */
	private void placeOrder(final BitfinexOrder newOrder) throws InterruptedException {
		if(SIMULATION) {
			return;
		}
		
		try {
			orderManager.placeOrderAndWaitUntilActive(newOrder);
		} catch(APIException e) {
			logger.error("Unable to place order", e);
		} 
	}

	/**
	 * Cancel the removed entry orders 
	 * Position is at the moment not interesting for an entry
	 * 
	 * @param entries
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void cancelRemovedEntryOrders(final Map<BitfinexCurrencyPair, CurrencyEntry> entries)
			throws APIException, InterruptedException {
		
		final List<ExchangeOrder> entryOrders = getAllOpenEntryOrders();
		
		for(final ExchangeOrder order : entryOrders) {
			final String symbol = order.getSymbol();
			final BitfinexCurrencyPair currencyPair = BitfinexCurrencyPair.fromSymbolString(symbol);
			
			if(! entries.containsKey(currencyPair)) {
				logger.info("Entry order for {} is not contained, canceling", currencyPair);
				
				cancelOrder(order);
			}
		}
	}
	
	/**
	 * Place the stop loss orders
	 * @param exits
	 */
	private void placeExitOrders(final Map<BitfinexCurrencyPair, Double> exits) 
			throws InterruptedException, APIException {
		
		logger.info("Process exit orders {}", exits);
		
		cleanupOldExitOrders(exits);
		
		placeNewExitOrders(exits);
	}

	/**
	 * Place the exit orders
	 * @param exits
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void placeNewExitOrders(final Map<BitfinexCurrencyPair, Double> exits)
			throws APIException, InterruptedException {
		
		for(final BitfinexCurrencyPair currency : exits.keySet()) {
			final ExchangeOrder order = getOpenOrderForSymbol(currency.toBitfinexString());
			final double exitPrice = exits.get(currency);
			
			// Check old orders
			if(order != null) {
				final double orderPrice = order.getPrice().doubleValue();
				
				if(orderPrice >= exitPrice || MathHelper.almostEquals(orderPrice, exitPrice)) {
					logger.info("Old order price for {} is fine (price: order {} model {})", 
							currency, orderPrice, exitPrice);
					continue;
				} 
				
				logger.info("Exit price for {} has moved form {} to {}, canceling old order", 
						currency, orderPrice, exitPrice);
				
				cancelOrder(order);
			} 
			
			final String currency1 = currency.getCurrency1();
			final double positionSize = getOpenPositionSizeForCurrency(currency1).doubleValue();
		
			// * -1.0 for sell order
			final double positionSizeSell = positionSize * -1.0;
			
			final BitfinexOrder newOrder = BitfinexOrderBuilder
					.create(currency, getOrderType(), positionSizeSell)
					.withPrice(exitPrice)
					.setPostOnly()
					.build();
	
			placeOrder(newOrder);
		}
	}

	/**
	 * Cleanup the old exit orders (remove duplicates, unknown orders)
	 * @param exits
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void cleanupOldExitOrders(final Map<BitfinexCurrencyPair, Double> exits)
			throws APIException, InterruptedException {
	
		final List<ExchangeOrder> oldExitOrders = getAllOpenExitOrders();
	
		// Remove unknown orders
		for(final ExchangeOrder order : oldExitOrders) {
			final BitfinexCurrencyPair symbol = BitfinexCurrencyPair.fromSymbolString(order.getSymbol());
			
			if(! exits.containsKey(symbol)) {
				logger.error("Found old and unknown order {}, canceling", order);
				
				cancelOrder(order);
			}
		}
		
		// Remove duplicates
		final Map<String, List<ExchangeOrder>> oldOrders = oldExitOrders.stream()
			             .collect(Collectors.groupingBy(ExchangeOrder::getSymbol));
		
		for(final String symbol : oldOrders.keySet()) {
			final List<ExchangeOrder> symbolOrderList = oldOrders.get(symbol);
			if(symbolOrderList.size() > 1) {
				logger.error("Found duplicates {}", symbolOrderList);
				
				for(final ExchangeOrder order : symbolOrderList) {
					cancelOrder(order);
				}
			}
		}		
	}

	/**
	 * Cancel the order and catch the exception
	 * @param order
	 * @throws APIException
	 * @throws InterruptedException
	 */
	private void cancelOrder(final ExchangeOrder order) throws InterruptedException {
		if(SIMULATION) {
			return;
		}
		
		try {
			orderManager.cancelOrderAndWaitForCompletion(order.getOrderId());
		} catch (APIException e) {
			logger.error("Got an exception while canceling the order", e);
		}
	}
	
	/**
	 * Calculate the position size
	 * @param upperValue
	 * @return
	 * @throws APIException 
	 */
	private double calculatePositionSize(final CurrencyEntry entry) throws APIException  {

		final BigDecimal entryPrice = new BigDecimal(entry.getEntryPrice());
		
		/**
		 * Calculate position size by max capital
		 */		
		// Max position size (capital)
		final BigDecimal positionSizePerCapital = getTotalPortfolioValueInUSD()
				.multiply(getInvestmentRate())
				.multiply(MAX_SINGLE_POSITION_SIZE)
				.divide(entryPrice);
		
		/**
		 * Calculate position size by max loss
		 */
		// Max loss per position
		final BigDecimal maxLossPerContract = new BigDecimal(entry.getEntryPrice() - entry.getStopLossPrice());
		
		// The total portfolio value
		final BigDecimal totalPortfolioValueInUSD = getTotalPortfolioValueInUSD().multiply(getInvestmentRate());
		
		// Max position size per stop loss
		final BigDecimal positionSizePerLoss = totalPortfolioValueInUSD
				.multiply(maxLossPerPosition)
				.divide(maxLossPerContract);

		// =============
		logger.info("Position size {} per capital is {}, position size per max loss is {}", 
				entry.getCurrencyPair(), positionSizePerCapital, positionSizePerLoss);
		
		final double positionSize = Math.min(positionSizePerCapital.doubleValue(), positionSizePerLoss.doubleValue());

		return roundPositionSize(positionSize);
	}

	/**
	 * Round the position size
	 * @param positionSize
	 * @return
	 */
	private double roundPositionSize(final double positionSize) {
		return MathUtil.round(positionSize, 6);
	}

	/**
	 * Get the open stop loss order
	 * @param symbol
	 * @param openOrders
	 * @return
	 * @throws APIException 
	 */
	private ExchangeOrder getOpenOrderForSymbol(final String symbol) throws APIException {
		
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getState() == ExchangeOrderState.STATE_ACTIVE)
			.filter(e -> e.getSymbol().equals(symbol))
			.findAny()
			.orElse(null);
	}
	
	/**
	 * Get all open entry orders
	 * @return 
	 * @throws APIException 
	 */
	private List<ExchangeOrder> getAllOpenEntryOrders() throws APIException {
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getState() == ExchangeOrderState.STATE_ACTIVE)
			.filter(e -> e.getAmount().doubleValue() > 0)
			.collect(Collectors.toList());
	}
	
	/**
	 * Get all open entry orders
	 * @return 
	 * @throws APIException 
	 */
	private List<ExchangeOrder> getAllOpenExitOrders() throws APIException {
		final List<ExchangeOrder> openOrders = bitfinexApiBroker.getOrderManager().getOrders();
		
		return openOrders.stream()
			.filter(e -> e.getOrderType() == getOrderType())
			.filter(e -> e.getState() == ExchangeOrderState.STATE_ACTIVE)
			.filter(e -> e.getAmount().doubleValue() <= 0)
			.collect(Collectors.toList());
	}
 	
	/**
	 * Get the exchange wallet
	 * @param currency 
	 * @return
	 * @throws APIException 
	 */
	protected Wallet getWalletForCurrency(final String currency) throws APIException {
		return bitfinexApiBroker.getWalletManager().getWallets()
			.stream()
			.filter(w -> w.getWalletType().equals(getWalletType()))
			.filter(w -> w.getCurreny().equals(currency))
			.findFirst()
			.orElse(null);
	}
	
	/**
	 * Get all wallets
	 * @param currency 
	 * @return
	 * @throws APIException 
	 */
	protected List<Wallet> getAllWallets() throws APIException {
		return bitfinexApiBroker.getWalletManager().getWallets()
			.stream()
			.filter(w -> w.getWalletType().equals(getWalletType()))
			.collect(Collectors.toList());
	}
	
	/**
	 * Is the given position open
	 * @param currency
	 * @return
	 * @throws APIException 
	 */
	public boolean isPositionOpen(final String currency) throws APIException {
		final Wallet wallet = getWalletForCurrency(currency);
		
		if(wallet == null) {
			// Unused wallets are not included in API communication
			logger.debug("Wallet for {} is null", currency);
			return false;
		}
		
		if(wallet.getBalance().doubleValue() > INVESTED_THRESHOLD) {
			return true;
		}
		
		return false;
	}
	
	@Override
	public String toString() {
		return "Portfolio manager: " + bitfinexApiBroker.getApiKey();
	}
	
	/*
	 * Abstract methods
	 */
	
	/**
	 * Get the used wallet type 
	 * @return
	 */
	protected abstract String getWalletType();
	
	/**
	 * Get the type for the orders
	 */
	protected abstract BitfinexOrderType getOrderType();

	/**
	 * Get the position size for the symbol
	 * @param symbol
	 * @return
	 * @throws APIException 
	 */
	protected abstract BigDecimal getOpenPositionSizeForCurrency(final String currency) throws APIException;

	/**
	 * Get the investment rate
	 * @return
	 */
	protected abstract BigDecimal getInvestmentRate();
	
	/**
	 * Get the total portfolio value in USD
	 * @throws APIException 
	 */
	protected abstract BigDecimal getTotalPortfolioValueInUSD() throws APIException;
	
	/**
	 * Get the available portfolio value in USD
	 * @throws APIException 
	 */
	protected abstract BigDecimal getAvailablePortfolioValueInUSD() throws APIException;
	

}
