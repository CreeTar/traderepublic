import groovy.json.*
import groovy.transform.Field

import java.text.DecimalFormat

@Field
def final pdfFolder = './2019'
@Field
def final dfRate = new DecimalFormat("0.000")
@Field
def final dfSaldo = new DecimalFormat("+#,##0.00;-#")

start()
def start() {
	def files = loadFiles(pdfFolder)
	def orders = parseFiles(files)
	def mapped = orders.groupBy { it.isin }
	printHeader()
	printSummary(mapped)
}

def printHeader() {
	print "${"Type".padRight(9, ' ')}"
	print "${"Date".padRight(15, ' ')}"
	print "${"Amount".padRight(10, ' ')}"
	print "${"Rate".padRight(10, ' ')}"
	print "${"Total".padRight(14, ' ')}"
	print "${"Invested".padRight(14, ' ')}"
	print "${"Dividend".padRight(14, ' ')}"
	println()
	println "-" * 90
}

def printOrder(order, winloss = null) {
	print "${order.type.padRight(4, ' ')}"
	print "${order.date.padLeft(15, ' ')}"
	print "${order.amount.toString().padLeft(14, ' ')}"
	print "x ${dfRate.format(order.rate).padLeft(14, ' ')}"
	print "${dfSaldo.format(order.total).padLeft(14, ' ')}"
	if (winloss) {
		print "${dfSaldo.format(winloss).padLeft(14, ' ')}"
	}
	println()
}

def printSummary(Map mapped) {
	def jsonData = []
	def allinvested = 0.00
	def alldividend = 0.00
	def allwinloss = 0.00
	def alltax = 0.00

	mapped.each { Map.Entry entry ->
		def isin = entry.value.first().isin
		def name = entry.value.first().name

		println entry.key
		def sortedByDate = entry.value.sort{ (it.date.split(/\./)).reverse().join("")	}
		def amount = 0
		def saldo = 0.00
		def div = 0.00
		def invested = 0.00
		def winloss = 0.00
		def tax = 0.00

		sortedByDate.each {
			def diff = null
			if (it.isBuy()) {
				saldo -= it.total
				amount += it.amount
				invested -= it.total
			} else if (it.isSell()) {
				def buyrate = invested / amount
				def buyval = (buyrate * it.amount).round(2)
				def sellval = it.total
				diff = sellval + buyval
				winloss += diff.round(2)
				tax += it.tax
				amount -= it.amount
				if (amount > 0) {
					invested += it.total - diff.round(2)
				} else {
					invested = 0
				}
			} else if (it.isDiv()) {
				div += it.total
			} else {
				throw new Exception("Kein Fleich kein Fich, komich.")
			}

			printOrder(it, diff)
		}

		def rate = (amount > 0) ? dfRate.format(saldo/amount) : "-.---"
		/*
		print "="
		print amount.toString().padLeft(28, ' ')

		print "x ${rate.padLeft(10, ' ')}"
		print dfSaldo.format(saldo).toString().padLeft(10, ' ')

		print dfSaldo.format(invested).toString().padLeft(14, ' ')
		print dfSaldo.format(div).toString().padLeft(14, ' ')
		 */
		if (amount > 0) {
			print "Open Shares: ${amount}x $rate,".padRight(30)
			print "Invested: ${dfSaldo.format(invested)}, ".padRight(25)
		} else {
			print "Open Shares: 0 x $rate,".padRight(30)
			print "Invested: -.--, ".padRight(25)
		}

		print "Winloss: ${dfSaldo.format(winloss)},".padRight(25)
		print "Dividend: ${dfSaldo.format(div)}"

		if (div > 0) {
			def percentDiv = ((div/Math.abs(invested))*100).round(2)
			print " ($percentDiv%)"
		}
		println()
		println "-" * 90

		allinvested += invested
		alldividend += div
		allwinloss += winloss
		alltax += tax
		
		if (amount > 0) {
			jsonData << [Name: name, Isin: isin, Amount: "$amount", Rate: Math.abs((saldo/amount).round(5)).toString()]
		}
	}

	println()

	println "=" * 90
	println "Total Invest:".padRight(23, ' ')  + dfSaldo.format(allinvested).padLeft(10, ' ')
	// println "Total Dividend:".padRight(23, ' ') + dfSaldo.format(alldividend).padLeft(10, ' ')
	println "Total WinLoss (Div.):".padRight(23, ' ') + "${dfSaldo.format(allwinloss+alldividend).padLeft(10, ' ')} (${dfSaldo.format(alldividend)})"
	println "Total Tax:".padRight(23, ' ') + dfSaldo.format(alltax).padLeft(10, ' ')
	println "=" * 90

	def file = new File("traderep.json")
	file.delete()
	file << JsonOutput.prettyPrint(JsonOutput.toJson(jsonData))
}

def loadFiles(path) {
	new File(path).listFiles(new FilenameFilter() {
		@Override
		boolean accept(File dir, String name) {
			return name.endsWith(".txt")
		}
	})
}

def parseFiles(files) {
	def orders = []
	files.each { File file ->
		try {
			orders << parseOrder(file.getText("Cp1252")).validate()
		} catch(Exception ex) {
			println file.name
			throw ex
		}
	}

	assert files.size() == orders.size()
	return orders
}

def String[] findGroup(String src, String pattern, String[] group = null) {
	def matcher = (src =~ pattern)
	def results = []
	if (group) {
		if (matcher.find()) {
			group.each { results << matcher.group(it).trim() }
		}
	} else {
		if (matcher.find()) {
			(1..matcher.groupCount()).each { results << matcher.group(it).trim() }
		}
	}
	return (results.any()) ? results : null
}

Order parseOrder(String text) {
	if (text.contains("KOSTENINFORMATION ZUM WERTPAPIERGESCHÄFT")) {
		throw new Exception("Wrong document type, expected WERTPAPIERABRECHNUNG, got KOSTENINFORMATION")
	}

	def order = new Order()
	order.date = findGroup(text, /(?m)^DATUM (.+)$/)?.first()

	def div = findGroup(text, /(?m)^Dividende mit dem Ex-Tag.(.+)\.$/)?.first()
	if (div) {
		order.date == div
		order.type = "DIV"
	} else {
		def type = findGroup(text, /(?m)^(?:Limit|Market)-Order.(.+).am/)?.first()
		if(!type) {
			throw new Exception("Order-Type not found")
		} else if (type.equalsIgnoreCase("KAUF")) {
			order.type = "BUY"
		} else if (type.equalsIgnoreCase("VERKAUF")) {
			order.type = "SELL"
		}

		order.addTax(findGroup(text, /(?m)^Quellensteuer ([-\d,. ]+) EUR$/)?.first())
		order.addTax(findGroup(text, /(?m)^Kapitalertragssteuer ([-\d,. ]+) EUR$/)?.first())
		order.addTax(findGroup(text, /(?m)^Solidaritätszuschlag ([-\d,. ]+) EUR$/)?.first())

		order.id = findGroup(text, /(?m)^ORDER (.+)$/)?.first()
		order.id += "#" + findGroup(text, /(?m)^AUSFÜHRUNG (.+)$/)?.first()
	}

	order.name = findGroup(text, /(?d)POSITION ANZAHL .+[\r\n]+(.+)[\r\n]+/)?.first()

	def (amount, rate) = findGroup(text, /(?m)^([.\d]+) Stk\. ([\d,.]+ EUR)/)
	if (!amount) {
		amount = findGroup(text, /(?m)^(?:Kauf|Verkauf) ([.\d]+) Stk\. (?:[\d,.]+ EUR)/)?.first()
		rate = findGroup(text, /(?m)^(?:Kauf|Verkauf) (?:[.\d]+) Stk\. ([\d,.]+ EUR)/)?.first()
	}

	if (!amount) {
		throw new Exception("Rate not found")
	}
	
	order.amount = amount
	order.rate = rate

	order.isin = findGroup(text, /(?m)^([A-Z]{2}[0-9A-Z]{10})$/)?.first()

	def total = findGroup(text, /(?m)^GESAMT ([-\d,. ]+) EUR$/)?.first()
	if (!total) {
		throw new Exception("Total was not found")
	}
	order.setTotal(total)

	return order
}

class Order {
	def date
	def id
	def type

	def name
	def isin
	int amount
	float rate
	float total
	def tax = 0.00

	def addTax(val) {
		tax += toDouble(val) ?: 0
	}

	def setAmount(val) {
		this.amount = toInt(val)
	}

	def setRate(val) {
		this.rate = toDouble(val)
	}

	def setTotal(val) {
		this.total = toDouble(val)
	}

	@Override
	String toString() {
		 return JsonOutput.prettyPrint(JsonOutput.toJson(this))
	}

	def isDiv() {
		return type == "DIV"
	}

	def isBuy() {
		return type == "BUY"
	}

	def isSell() {
		return type == "SELL"
	}

	def validate() {
		assert date && date.size() == 10 && date.split(/\./).size() == 3
		assert isBuy() || isSell() || isDiv()
		if (!isDiv()) {
			assert id && id.split("#").size() == 2 && !id.contains("null")
		}
		assert name
		assert isin && isin.size() == 12
		assert amount && amount > 0
		assert rate && rate > 0
		assert total && total > 0

		if (isBuy()) {
			float calced = (amount * rate).round(2)
			assert calced == total
		}

		return this
	}

	String numberfy(val) {
		final map = ["EUR": "", ".": "", ",": "."]
		return val?.replace(map)
	}

	def toInt(val) {
		return numberfy(val)?.toInteger()
	}

	def toDouble(val) {
		return numberfy(val)?.toDouble()
	}
}
