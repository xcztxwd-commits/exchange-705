<template>
  <div class="h-screen w-screen flex flex-col bg-white dark:bg-[#131722] text-gray-800 dark:text-gray-100 text-sm overflow-hidden font-sans">
    <!-- Top Nav -->
    <header class="h-14 border-b border-gray-200 dark:border-[#2b3139] flex justify-between items-center shrink-0 shadow-sm bg-white dark:bg-[#131722] z-10 relative">
      <div class="flex items-center px-4 absolute left-0 h-full w-[300px]">
        <div class="flex-1 flex justify-center">
          <div class="font-bold text-lg truncate">GTCFX</div>
        </div>
        <button class="bg-[#8cc63f] text-white px-4 py-1.5 rounded text-sm font-medium shadow-sm shrink-0">{{ localeStore.t('productList') }}</button>
      </div>
      
      <div class="flex items-center space-x-1 absolute left-[300px] h-full px-4 border-l border-gray-200 dark:border-[#2b3139]">
        <button v-for="i in ['1m', '5m', '15m', '30m', '1h', '1d']" :key="i"
                @click="currentInterval = i"
                :class="['px-3 py-1.5 rounded text-sm font-medium transition-colors uppercase', currentInterval === i ? 'bg-[#8cc63f] text-white shadow-sm' : 'hover:bg-gray-100 dark:hover:bg-[#2b3139] dark:bg-[#2b3139] text-gray-600 dark:text-gray-300']">
          {{ i }}
        </button>
      </div>
      
      <div class="flex items-center space-x-6 text-gray-600 dark:text-gray-300 font-medium pr-4 absolute right-0 h-full">
        <button @click="showCreditLoan = true" class="hover:text-[#8cc63f] flex items-center transition-colors"><el-icon class="mr-1 text-lg"><Money /></el-icon> {{ localeStore.t('creditLoan') }}</button>
        <button @click="showWealth = true" class="hover:text-[#8cc63f] flex items-center transition-colors"><el-icon class="mr-1 text-lg"><Coin /></el-icon> {{ localeStore.t('financialManagement') }}</button>
        <el-dropdown trigger="click" @command="handleLangChange">
          <span class="el-dropdown-link cursor-pointer flex items-center hover:text-[#8cc63f] transition-colors">
            {{ currentLangLabel }} <el-icon class="el-icon--right"><arrow-down /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="lang in languages" :key="lang.locale" :command="lang.locale">{{ lang.label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        
        <el-switch v-model="isDarkMode" @change="toggleDarkMode" class="ml-2" style="--el-switch-on-color: #2c2c2c; --el-switch-off-color: #e5e7eb">
          <template #active-action>
            <el-icon><Moon /></el-icon>
          </template>
          <template #inactive-action>
            <el-icon><Sunny /></el-icon>
          </template>
        </el-switch>

        <template v-if="auth.token">
          <button @click="showUserCenter = true" class="hover:text-[#8cc63f] transition-colors">{{ auth.user?.email || '111@test.com' }}</button>
        </template>
        <template v-else>
          <button @click="showLoginModal = true" class="hover:text-[#8cc63f] transition-colors font-bold">{{ localeStore.t('login') }}</button>
          <button @click="showRegisterModal = true" class="bg-[#8cc63f] text-white px-4 py-1.5 rounded text-sm font-bold shadow-sm hover:bg-[#7ab036] transition-colors">{{ localeStore.t('register') }}</button>
        </template>
      </div>
    </header>

    <div class="flex flex-1 overflow-hidden">
      <!-- Left Sidebar -->
      <aside class="w-[300px] border-r border-gray-200 dark:border-[#2b3139] flex flex-col shrink-0 bg-white dark:bg-[#131722] z-10 shadow-[2px_0_8px_rgba(0,0,0,0.02)]">
        <div class="p-3 border-b border-gray-200 dark:border-[#2b3139] flex space-x-2">
          <el-input v-model="searchQuery" placeholder="" clearable class="custom-search flex-1 w-full">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="currentCategory" :placeholder="localeStore.t('pleaseSelect')" class="w-[110px] custom-select-small shrink-0">
            <el-option :label="localeStore.t('all')" value="" />
            <el-option v-for="cat in (marketStore as any).categories" :key="cat.key || cat.id" :label="cat.label || cat.name" :value="cat.key || cat.code" />
          </el-select>
        </div>
        <div class="flex-1 overflow-y-auto custom-scrollbar">
          <div v-for="symbol in filteredSymbols" :key="symbol.symbol"
               @click="selectSymbol(symbol)"
               :class="['flex justify-between items-center p-3 cursor-pointer border-b border-gray-200 dark:border-[#2b3139] transition-colors', currentSymbol === symbol.symbol ? 'bg-gray-50 dark:bg-[#181c27] border-l border-gray-200 dark:border-[#363c4e]-4 border-l border-gray-200 dark:border-[#363c4e]-[#8cc63f]' : 'bg-white dark:bg-[#131722] hover:bg-gray-50 dark:hover:bg-[#181c27] dark:bg-[#181c27] border-l border-gray-200 dark:border-[#363c4e]-4 border-l border-gray-200 dark:border-[#363c4e]-transparent']">
            <div class="flex items-center space-x-3 w-[45%]">
              <img v-if="symbol.iconUrl" :src="getImageUrl(symbol.iconUrl)" class="w-8 h-8 rounded-full object-contain shrink-0" />
              <div class="w-8 h-8 rounded-full bg-gray-100 dark:bg-[#2b3139] flex items-center justify-center text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 font-bold border border-gray-200 dark:border-[#2b3139] shadow-inner shrink-0" v-else>{{ symbol.symbol.substring(0,1) }}</div>
              <span class="font-bold text-gray-700 dark:text-gray-200 truncate">{{ symbol.symbol }}</span>
            </div>
            <div class="flex-1 text-center">
              <span :class="['font-bold font-mono', parseFloat(getSymbolChange(symbol.symbol)) >= 0 ? 'text-[#8cc63f]' : 'text-[#ff4d4f]']">{{ getSymbolPrice(symbol.symbol) }}</span>
            </div>
            <div class="flex flex-col items-end w-[25%]">
              <span :class="['px-2 py-1 rounded text-white text-[12px] font-bold w-full text-center', parseFloat(getSymbolChange(symbol.symbol)) >= 0 ? 'bg-[#8cc63f]' : 'bg-[#ff4d4f]']">
                {{ parseFloat(getSymbolChange(symbol.symbol)) > 0 ? '+' : '' }}{{ getSymbolChange(symbol.symbol) }}%
              </span>
            </div>
          </div>
        </div>
      </aside>

      <!-- Center Chart -->
      <div class="flex-1 flex flex-col min-w-0 bg-white dark:bg-[#131722]">
        <div class="px-6 py-2 border-b border-gray-200 dark:border-[#2b3139] shrink-0 flex justify-between items-center">
           <div>
             <div class="text-xl font-bold text-gray-800 dark:text-gray-100 tracking-tight">{{ currentSymbol }}</div>
             <div class="text-gray-400 dark:text-gray-500 text-xs flex space-x-4 mt-0.5 font-mono">
               <span>time {{ currentTime }}</span>
               <span>open <span class="text-gray-600 dark:text-gray-300">{{ currentKline.open }}</span></span>
               <span>high <span class="text-gray-600 dark:text-gray-300">{{ currentKline.high }}</span></span>
               <span>low <span class="text-gray-600 dark:text-gray-300">{{ currentKline.low }}</span></span>
               <span>close <span class="text-gray-600 dark:text-gray-300">{{ currentKline.close }}</span></span>
             </div>
           </div>
        </div>
        <div class="flex-1 relative bg-[#fafafa]">
           <KlineChart :symbol="currentSymbol" :category="currentCategory" :interval="currentInterval" class="w-full h-full absolute inset-0" />
        </div>
        
        <!-- Bottom Panel -->
        <div class="h-[280px] border-t border-gray-200 dark:border-[#2b3139] flex flex-col shrink-0 bg-white dark:bg-[#131722] shadow-[0_-2px_8px_rgba(0,0,0,0.02)] z-10">
           <div class="flex border-b border-gray-200 dark:border-[#2b3139] px-2 pt-2">
              <button @click="tradeMode = 'contract'; orderSubTab = 'positions'; loadContractOrders()" :class="['px-6 py-2.5 font-bold text-base transition-colors border-b border-gray-200 dark:border-[#2b3139]-2', tradeMode === 'contract' ? 'text-[#8cc63f] border-[#8cc63f]' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-800 dark:text-gray-100 border-transparent']">{{ localeStore.t('contract') }}</button>
              <button @click="tradeMode = 'options'; orderSubTab = 'positions'; loadOptionOrders()" :class="['px-6 py-2.5 font-bold text-base transition-colors border-b border-gray-200 dark:border-[#2b3139]-2', tradeMode === 'options' ? 'text-[#8cc63f] border-[#8cc63f]' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-800 dark:text-gray-100 border-transparent']">{{ localeStore.t('optionsTerm') }}</button>
           </div>
           <div class="flex border-b border-gray-200 dark:border-[#2b3139] text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-[#181c27]/50">
              <button @click="orderSubTab = 'positions'" :class="['px-6 py-2 font-bold border-b border-gray-200 dark:border-[#2b3139]-2 transition-colors', orderSubTab === 'positions' ? 'text-[#8cc63f] border-[#8cc63f]' : 'hover:text-gray-800 dark:text-gray-100 border-transparent']">{{ localeStore.t('positions') }}</button>
              <button v-if="tradeMode === 'contract'" @click="orderSubTab = 'pending'" :class="['px-6 py-2 font-bold border-b border-gray-200 dark:border-[#2b3139]-2 transition-colors', orderSubTab === 'pending' ? 'text-[#8cc63f] border-[#8cc63f]' : 'hover:text-gray-800 dark:text-gray-100 border-transparent']">{{ localeStore.t('pendingOrders') }}</button>
              <button @click="orderSubTab = 'history'" :class="['px-6 py-2 font-bold border-b border-gray-200 dark:border-[#2b3139]-2 transition-colors', orderSubTab === 'history' ? 'text-[#8cc63f] border-[#8cc63f]' : 'hover:text-gray-800 dark:text-gray-100 border-transparent']">{{ localeStore.t('historyRecords') }}</button>
              <div v-if="tradeMode === 'contract'" class="flex-1 flex justify-end items-center px-6 space-x-6 text-xs">
                 <span>{{ localeStore.t('profitAndLoss') }}: <span :class="['font-bold text-sm', totalProfit >= 0 ? 'text-[#8cc63f]' : 'text-[#ff4d4f]']">{{ totalProfit.toFixed(2) }}</span></span>
                 <span>{{ localeStore.t('margin') }}: <span class="font-medium text-gray-700 dark:text-gray-200">{{ totalMargin.toFixed(4) }}</span></span>
                 <span>{{ localeStore.t('riskRate') }}: <span class="font-medium text-gray-700 dark:text-gray-200">{{ riskRate.toFixed(2) }}%</span></span>
              </div>
           </div>
           <div class="flex-1 overflow-y-auto custom-scrollbar">
             <!-- 合约订单表格 -->
             <table v-if="tradeMode === 'contract'" class="w-full text-left text-xs">
               <thead class="text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-[#181c27]/80 sticky top-0 font-medium">
                 <tr>
                   <th class="py-3 px-4 font-medium whitespace-nowrap">{{ localeStore.t('symbol') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('orderNumber') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('direction') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('lots') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('openPrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('currentPrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('takeProfitPrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('stopLossPrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('handlingFee') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('margin') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('profitAndLoss') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('openTimeLabel') }}</th>
                   <th class="py-3 px-4 font-medium whitespace-nowrap text-right">{{ localeStore.t('action') }}</th>
                 </tr>
               </thead>
               <tbody>
                 <tr v-if="currentOrderList.length === 0">
                   <td colspan="13" class="text-center py-8 text-gray-400 dark:text-gray-500">{{ localeStore.t('noData') }}</td>
                 </tr>
                 <tr v-for="order in currentOrderList" :key="order.id" class="border-b border-gray-200 dark:border-[#2b3139] hover:bg-gray-50 dark:hover:bg-[#181c27] dark:bg-[#181c27] transition-colors group">
                    <td class="py-3 px-4 font-bold text-gray-700 dark:text-gray-200">{{ order.symbol }}</td>
                    <td class="py-3 px-2 text-gray-500 dark:text-gray-400 dark:text-gray-500">#{{ order.id }}</td>
                    <td class="py-3 px-2"><span :class="['text-white px-2 py-0.5 rounded text-[11px] font-bold', order.type === 'buy' ? 'bg-[#8cc63f]' : 'bg-[#ff4d4f]']">{{ order.type === 'buy' ? localeStore.t('buy') : localeStore.t('sell') }}</span></td>
                    <td class="py-3 px-2">{{ order.lots }}</td>
                    <td class="py-3 px-2 font-mono">{{ order.openPrice.toFixed(4) }}</td>
                    <td class="py-3 px-2 font-mono font-bold text-gray-700 dark:text-gray-200">{{ order.currentPrice.toFixed(4) }}</td>
                    <td class="py-3 px-2 text-gray-400 dark:text-gray-500">{{ order.takeProfit || 0 }}</td>
                    <td class="py-3 px-2 text-gray-400 dark:text-gray-500">{{ order.stopLoss || 0 }}</td>
                    <td class="py-3 px-2">{{ order.fee.toFixed(2) }}</td>
                    <td class="py-3 px-2">{{ order.margin.toFixed(2) }}</td>
                    <td :class="['py-3 px-2 font-bold', order.profit >= 0 ? 'text-[#8cc63f]' : 'text-[#ff4d4f]']">{{ order.profit.toFixed(2) }}</td>
                    <td class="py-3 px-2 text-gray-400 dark:text-gray-500" v-html="order.openTime.replace(' ', '<br/>')"></td>
                    <td class="py-3 px-4 text-right space-x-2">
                      <button v-if="orderSubTab === 'positions'" @click="openTpSlModal(order)" class="bg-[#8cc63f] text-white px-2 py-1 rounded text-[11px] font-medium hover:bg-[#7ab036] transition-colors">TP/SL</button>
                      <button v-if="orderSubTab === 'positions'" @click="closePosition(order)" class="bg-[#ff4d4f] text-white px-3 py-1 rounded text-[11px] font-medium hover:bg-[#e64042] transition-colors">{{ localeStore.t('closePosition') }}</button>
                      <button v-if="orderSubTab === 'pending'" @click="cancelOrder(order)" class="bg-gray-400 text-white px-3 py-1 rounded text-[11px] font-medium hover:bg-gray-500 transition-colors">{{ localeStore.t('cancelOrder') }}</button>
                    </td>
                 </tr>
               </tbody>
             </table>
             
             <!-- 期限订单表格 -->
             <table v-else class="w-full text-left text-xs">
               <thead class="text-gray-400 dark:text-gray-500 bg-gray-50 dark:bg-[#181c27]/80 sticky top-0 font-medium">
                 <tr>
                   <th class="py-3 px-4 font-medium whitespace-nowrap">{{ localeStore.t('symbol') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('direction') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('amountShort') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('buyPrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('settlePrice') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('periodSec') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('estProfit') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('actualProfit') }}</th>
                   <th class="py-3 px-2 font-medium whitespace-nowrap">{{ localeStore.t('status') }}</th>
                   <th class="py-3 px-4 font-medium whitespace-nowrap text-right">{{ localeStore.t('timeLabel') }}</th>
                 </tr>
               </thead>
               <tbody>
                 <tr v-if="currentOrderList.length === 0">
                   <td colspan="10" class="text-center py-8 text-gray-400 dark:text-gray-500">{{ localeStore.t('noneText') }}{{ localeStore.t('recordText') }}</td>
                 </tr>
                 <tr v-for="order in currentOrderList" :key="order.id" class="border-b border-gray-200 dark:border-[#2b3139] hover:bg-gray-50 dark:hover:bg-[#181c27] dark:bg-[#181c27] transition-colors">
                    <td class="py-3 px-4 font-bold text-gray-700 dark:text-gray-200">{{ order.symbol }}</td>
                    <td class="py-3 px-2"><span :class="['text-white px-2 py-0.5 rounded text-[11px] font-bold', order.type === 'buy' || order.type === 'up' ? 'bg-[#8cc63f]' : 'bg-[#ff4d4f]']">{{ order.type === 'buy' || order.type === 'up' ? localeStore.t('buyUpText') : localeStore.t('buyDownText') }}</span></td>
                    <td class="py-3 px-2 font-mono">{{ order.amount ? order.amount.toFixed(2) : '0.00' }}</td>
                    <td class="py-3 px-2 font-mono">{{ order.openPrice ? order.openPrice.toFixed(4) : '0.0000' }}</td>
                    <td class="py-3 px-2 font-mono">{{ order.closePrice && order.closePrice > 0 ? order.closePrice.toFixed(4) : '-' }}</td>
                    <td class="py-3 px-2">{{ order.period }}</td>
                    <td class="py-3 px-2 text-[#8cc63f]">{{ order.expectedProfit ? order.expectedProfit.toFixed(2) : '0.00' }}</td>
                    <td :class="['py-3 px-2 font-bold', order.profit > 0 ? 'text-[#8cc63f]' : (order.profit < 0 ? 'text-[#ff4d4f]' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500')]">{{ order.status === 'CLOSED' ? (order.profit ? order.profit.toFixed(2) : '0.00') : '-' }}</td>
                    <td class="py-3 px-2">
                      <span v-if="order.status === 'TRADING'" class="text-[#8cc63f] bg-green-50 px-2 py-1 rounded">{{ localeStore.t('tradingStatus') }}</span>
                      <span v-else class="text-gray-500 dark:text-gray-400 dark:text-gray-500 bg-gray-100 dark:bg-[#2b3139] px-2 py-1 rounded">{{ localeStore.t('settled') }}</span>
                    </td>
                    <td class="py-3 px-4 text-right text-gray-400 dark:text-gray-500" v-html="order.openTime.replace(' ', '<br/>')"></td>
                 </tr>
               </tbody>
             </table>
           </div>
        </div>
      </div>

      <!-- Right Sidebar: Order Form -->
      <aside class="w-[320px] border-l border-gray-200 dark:border-[#2b3139] flex flex-col shrink-0 bg-white dark:bg-[#131722] z-10 shadow-[-2px_0_8px_rgba(0,0,0,0.02)]">
        <div class="p-4 bg-[#8cc63f] text-white mx-4 mt-4 rounded-lg shadow-md bg-gradient-to-r from-[#8cc63f] to-[#9cd64f]">
          <div class="text-sm opacity-90 font-medium">{{ localeStore.t('availableFund') }}</div>
          <div class="text-2xl font-bold mt-1 mb-0 font-mono tracking-tight">
            ${{ tradeMode === 'contract' ? contractBalance.toFixed(2) : optionBalance.toFixed(2) }}
          </div>
        </div>
        
        <div class="flex text-center bg-gray-100 dark:bg-[#2b3139] p-1 mx-4 mt-4 rounded-md">
           <button @click="tradeMode = 'contract'; orderSubTab = 'positions'; loadContractOrders()" :class="['flex-1 py-1.5 rounded font-bold text-sm transition-all', tradeMode === 'contract' ? 'bg-white dark:bg-[#131722] text-[#8cc63f] shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('contract') }}</button>
           <button @click="tradeMode = 'options'; orderSubTab = 'positions'; loadOptionOrders()" :class="['flex-1 py-1.5 rounded font-bold text-sm transition-all', tradeMode === 'options' ? 'bg-white dark:bg-[#131722] text-[#8cc63f] shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('optionsTerm') }}</button>
        </div>

        <!-- Contract Form -->
        <div v-if="tradeMode === 'contract'" class="p-4 flex-1 overflow-y-auto custom-scrollbar">
           <div class="flex justify-between items-center mb-6 border-b border-gray-200 dark:border-[#2b3139] pb-4">
             <span class="font-bold text-lg text-gray-800 dark:text-gray-100">{{ currentSymbol }}</span>
             <span :class="['text-xl font-bold font-mono tracking-tight', getPriceColor(currentSymbolObj)]">{{ getSymbolPrice(currentSymbol) }}</span>
           </div>
           
           <el-select v-model="orderType" class="w-full mb-5 custom-select">
             <el-option :label="localeStore.t('marketPrice')" value="market" />
             <el-option :label="localeStore.t('limitPrice')" value="limit" />
           </el-select>

           <div v-if="orderType === 'limit'" class="mb-5">
             <div class="text-gray-600 dark:text-gray-300 mb-2 font-medium text-sm">{{ localeStore.t('orderPrice') }}</div>
             <el-input-number v-model="limitPrice" class="w-full custom-input-number" :controls="true" :min="0" :step="0.01" />
           </div>

           <div class="space-y-5">
             <div>
               <div class="flex items-center justify-between mb-2">
                 <span class="text-gray-600 dark:text-gray-300 font-medium text-sm">{{ localeStore.t('stopLossPrice') }}</span>
                 <el-switch v-model="useStopLoss" style="--el-switch-on-color: #8cc63f;" />
               </div>
               <el-input-number v-if="useStopLoss" v-model="stopLossPrice" class="w-full custom-input-number" :controls="true" />
               <div v-else class="w-full h-10 bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139] rounded flex items-center justify-center text-gray-300 font-mono">0</div>
             </div>

             <div>
               <div class="flex items-center justify-between mb-2">
                 <span class="text-gray-600 dark:text-gray-300 font-medium text-sm">{{ localeStore.t('takeProfitPrice') }}</span>
                 <el-switch v-model="useTakeProfit" style="--el-switch-on-color: #8cc63f;" />
               </div>
               <el-input-number v-if="useTakeProfit" v-model="takeProfitPrice" class="w-full custom-input-number" :controls="true" />
               <div v-else class="w-full h-10 bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139] rounded flex items-center justify-center text-gray-300 font-mono">0</div>
             </div>

             <div class="pt-2">
               <div class="text-gray-600 dark:text-gray-300 mb-2 font-medium text-sm">{{ localeStore.t('tradingAmount') }}(Step:0.01)</div>
               <el-input-number v-model="quantity" :min="0.01" :step="0.01" class="w-full custom-input-number" />
             </div>

             <div class="bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg text-xs text-gray-500 dark:text-gray-400 dark:text-gray-500 space-y-2 mt-4 border border-gray-100 dark:border-[#2b3139]">
               <div class="flex justify-between items-center"><span class="font-medium text-gray-600 dark:text-gray-300">{{ localeStore.t('perLot') }}</span><span class="font-mono text-gray-800 dark:text-gray-100 font-medium">1 {{ localeStore.t('lot') }} = {{ lotSize }} {{ currentSymbol }}</span></div>
               <div class="flex justify-between items-center"><span class="font-medium text-gray-600 dark:text-gray-300">{{ localeStore.t('estFee') }}</span><span class="font-mono text-gray-800 dark:text-gray-100 font-medium">{{ estimatedFee.toFixed(6) }}</span></div>
               <div class="flex justify-between items-center"><span class="font-medium text-gray-600 dark:text-gray-300">{{ localeStore.t('estMargin') }}</span><span class="font-mono text-gray-800 dark:text-gray-100 font-medium">{{ estimatedMargin.toFixed(2) }}</span></div>
               <div class="flex justify-between items-center"><span class="font-medium text-gray-600 dark:text-gray-300">{{ localeStore.t('balance') }}</span><span class="font-mono text-gray-800 dark:text-gray-100 font-medium">{{ formatMoney(contractBalance) }}</span></div>
             </div>

             <div class="flex space-x-3 pt-4">
               <button @click="submitContractOrder('BUY')" class="flex-1 bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200">{{ localeStore.t('buy') }}</button>
               <button @click="submitContractOrder('SELL')" class="flex-1 bg-[#ff4d4f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#e64042] transition-colors shadow-sm shadow-red-200">{{ localeStore.t('sell') }}</button>
             </div>
           </div>
        </div>

        <!-- Options Form -->
        <div v-else class="p-4 flex-1 overflow-y-auto custom-scrollbar">
           <div class="flex justify-between items-center mb-6 border-b border-gray-200 dark:border-[#2b3139] pb-4">
             <span class="font-bold text-lg text-gray-800 dark:text-gray-100">{{ currentSymbol }}</span>
             <span :class="['text-xl font-bold font-mono tracking-tight', getPriceColor(currentSymbolObj)]">{{ getSymbolPrice(currentSymbol) }}</span>
           </div>

           <div class="mb-6 bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg border border-gray-100 dark:border-[#2b3139]">
             <div class="text-gray-700 dark:text-gray-200 mb-3 font-bold text-sm">{{ localeStore.t('selectExpiry') }}</div>
             <div class="grid grid-cols-3 gap-2">
               <button v-for="d in optionDurations" :key="d.duration"
                       @click="optionTime = d.duration"
                       :class="['py-2 rounded border border-gray-200 dark:border-[#2b3139] font-medium text-sm transition-colors', optionTime === d.duration ? 'bg-[#8cc63f] text-white border border-gray-200 dark:border-[#2b3139]-[#8cc63f] shadow-sm' : 'bg-white dark:bg-[#131722] text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-[#181c27] dark:bg-[#181c27] hover:border border-gray-200 dark:border-[#2b3139]-gray-300']">
                 {{ d.duration }}s
               </button>
             </div>
           </div>

           <div class="flex justify-between items-center text-sm mb-6 px-1">
             <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium">{{ localeStore.t('expectedReturn') }}{{ localeStore.t('rate') }}</span>
             <span class="font-bold text-gray-800 dark:text-gray-100 text-lg">{{ (currentOptionProfitRate * 100).toFixed(0) }}%</span>
           </div>

           <div class="mb-6">
             <div class="text-gray-700 dark:text-gray-200 mb-2 font-bold text-sm">{{ localeStore.t('tradingAmount') }} (>=50)</div>
             <el-input v-model="optionAmount" :placeholder="localeStore.t('pleaseEnterQuantity')" type="number" class="custom-input-large" />
           </div>

           <div class="flex justify-between items-center text-sm mb-8 px-1">
             <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium">{{ localeStore.t('expectedReturn') }}</span>
             <span class="font-bold text-gray-800 dark:text-gray-100 text-lg font-mono">{{ expectedOptionProfit }} USD</span>
           </div>

           <div class="flex space-x-3">
             <button @click="submitOptionOrder('UP')" class="flex-1 bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200">{{ localeStore.t('buyUp') }}</button>
             <button @click="submitOptionOrder('DOWN')" class="flex-1 bg-[#ff4d4f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#e64042] transition-colors shadow-sm shadow-red-200">{{ localeStore.t('buyDown') }}</button>
           </div>
        </div>
      </aside>
    </div>

    <!-- Modals -->
    <el-dialog v-model="showFinancialPurchase" :title="localeStore.t('financialPurchase')" width="500px" class="custom-dialog rounded-xl overflow-hidden">
      <div v-if="activeFinancialProduct" class="space-y-5 px-2">
        <div class="bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg border border-gray-100 dark:border-[#2b3139] flex items-center space-x-4 mb-4">
          <img :src="getImageUrl(activeFinancialProduct.imageUrl)" class="w-12 h-12 rounded-full object-contain bg-white dark:bg-[#131722] shadow-sm p-1" />
          <div>
            <div class="font-bold text-gray-800 dark:text-gray-100 text-lg">{{ activeFinancialProduct.name }}</div>
            <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ activeFinancialProduct.currency }}</div>
          </div>
        </div>

        <div class="flex flex-col space-y-1">
          <div class="flex items-center border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-3 focus-within:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus-within:ring-1 focus-within:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]">
            <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 w-16 font-medium">{{ localeStore.t('amountLabel') }}</span>
            <input v-model="financialPurchaseAmount" type="number" class="flex-1 outline-none text-gray-800 dark:text-gray-100 font-mono text-base bg-transparent" :placeholder="`(${formatMoney(activeFinancialProduct.minPurchase)} - ${formatMoney(activeFinancialProduct.maxPurchase)})`" />
            <button @click="setMaxFinancialPurchase" class="text-white text-xs ml-2 bg-[#8cc63f] px-3 py-1.5 rounded font-bold hover:bg-[#7ab036] transition-colors shadow-sm">{{ localeStore.t('maxButton') }}</button>
          </div>
          <div class="flex justify-between text-xs text-gray-400 dark:text-gray-500 px-1">
            <span>{{ localeStore.t('minimum') }} {{ formatMoney(activeFinancialProduct.minPurchase) }}</span>
            <span>{{ localeStore.t('maximum') }} {{ formatMoney(activeFinancialProduct.maxPurchase) }}</span>
          </div>
        </div>

        <div class="text-sm text-gray-600 dark:text-gray-300 space-y-3 mt-4 bg-gray-50 dark:bg-[#181c27] p-5 rounded-lg border border-gray-100 dark:border-[#2b3139]">
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('estDailyYield') }}</span><span class="font-bold text-[#8cc63f]">{{ Number(activeFinancialProduct.dailyYieldRate).toFixed(2) }}%</span></div>
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('lockRent') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ formatMoney(activeFinancialProduct.rentalFee) }}</span></div>
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('financePeriod') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ activeFinancialProduct.termDays }}{{ localeStore.t('days') }}</span></div>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="submitFinancialPurchase" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('confirmPurchase') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showLoanContract" :title="localeStore.t('loanAgreementQuote')" width="600px" class="custom-dialog rounded-xl">
      <div v-if="currentLoanRecord" class="p-4 h-[60vh] overflow-y-auto custom-scrollbar text-sm text-gray-700 dark:text-gray-200 leading-relaxed">
        <div class="font-bold text-lg mb-2">{{ localeStore.t('loanAgreementTitle') }}("{{ currentLoanRecord.id }}"){{ localeStore.t('dateText') }}</div>
        <div class="text-[#8cc63f] mb-4">{{ currentLoanRecord.createdAt }}</div>
        <div class="mb-4">（「{{ localeStore.t('effectiveText') }}{{ localeStore.t('dateText') }}」）{{ localeStore.t('signByPartiesText3') }}：</div>

        <div class="mb-6">
          <div class="font-bold text-base mb-2">{{ localeStore.t('borrowerQuote') }}</div>
          <div class="flex mb-1"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('firstNameQuote') }}</span> <span class="font-medium">{{ currentLoanRecord.realName ? currentLoanRecord.realName.charAt(0) : localeStore.t('unknownText') }}</span></div>
          <div class="flex mb-1"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('lastNameQuote') }}</span> <span class="font-medium">{{ currentLoanRecord.realName ? currentLoanRecord.realName.substring(1) : localeStore.t('unknownText') }}</span></div>
          <div class="flex mb-1"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('addressQuote') }}</span> <span class="font-medium">{{ currentLoanRecord.address || localeStore.t('noneText') }}</span></div>
          <div class="flex mb-1"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('phoneQuote') }}</span> <span class="font-medium">{{ currentLoanRecord.phone || localeStore.t('noneText') }}</span></div>
          <div class="flex mb-2"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('nameQuote') }}</span> <span class="font-medium">{{ currentLoanRecord.realName || localeStore.t('unknownText') }}</span></div>
          <div>{{ localeStore.t('partiesQuote') }}</div>
        </div>

        <div class="mb-6">
          <div class="font-bold text-base mb-2">{{ localeStore.t('repaymentTermsQuote') }}</div>
          <div class="flex mb-2"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('amountQuote') }}</span> <span class="text-[#8cc63f] font-bold">{{ formatMoney(currentLoanRecord.amount) }}</span></div>
          <div>{{ localeStore.t('borrowerAgreesToRepayQuote') }}<span class="text-[#8cc63f] font-bold">{{ formatMoney(currentLoanRecord.amount) }}</span>{{ localeStore.t('loanQuote') }}</div>
        </div>

        <div class="mb-6">
          <div class="font-bold text-base mb-2">{{ localeStore.t('termsQuote') }}</div>
          <div class="flex mb-2"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('interestRateQuote') }}</span> <span class="text-[#8cc63f] font-bold">{{ currentLoanRecord.dailyRate }}%</span></div>
          <div class="mb-2">{{ localeStore.t('partiesAgreeInterestQuote') }}<span class="text-[#8cc63f] font-bold">{{ currentLoanRecord.dailyRate }}%</span>{{ localeStore.t('calculatedDailyQuote') }}</div>
          <div class="flex mb-2"><span class="w-16 text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('loanTermQuote') }}</span> <span class="text-[#8cc63f] font-bold">{{ currentLoanRecord.days }}{{ localeStore.t('days') }}</span></div>
          <div class="mb-2">{{ localeStore.t('loanTermIsQuote') }}<span class="text-[#8cc63f] font-bold">{{ currentLoanRecord.days }}</span>{{ localeStore.t('daysQuote') }}</div>
          <div class="mb-2">{{ localeStore.t('repaymentMethodQuote') }}</div>
          <div>{{ localeStore.t('borrowerAgreesToRepayBeforeMaturityQuote') }}<span class="text-[#8cc63f] font-bold">{{ formatMoney(currentLoanRecord.amount) }}</span>{{ localeStore.t('principalAndQuote') }}<span class="text-[#8cc63f] font-bold">{{ formatMoney(currentLoanRecord.totalInterest) }}</span>{{ localeStore.t('interestQuote') }}</div>
        </div>
      </div>
      <template #footer>
        <div class="px-4 pb-4" v-if="!currentLoanRecord?.contractSigned">
          <button @click="openLoanSign" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('signQuote') }}</button>
        </div>
        <div class="px-4 pb-4" v-else>
          <button disabled class="w-full bg-gray-200 dark:bg-[#363c4e] text-gray-500 dark:text-gray-400 dark:text-gray-500 py-3.5 rounded-lg font-bold text-base cursor-not-allowed">{{ localeStore.t('alreadySigned') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showLoanSign" :title="localeStore.t('pleaseSignQuote')" width="500px" class="custom-dialog rounded-xl" :close-on-click-modal="false">
      <div class="p-4">
        <div class="border border-gray-200 dark:border-[#2b3139]-2 border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 rounded-lg bg-gray-50 dark:bg-[#181c27] mb-4 overflow-hidden relative" style="height: 300px;">
          <canvas
            ref="signCanvasRef"
            class="w-full h-full cursor-crosshair touch-none"
            @mousedown="startDraw"
            @mousemove="draw"
            @mouseup="endDraw"
            @mouseleave="endDraw"
            @touchstart.prevent="startDraw"
            @touchmove.prevent="draw"
            @touchend.prevent="endDraw"
          ></canvas>
        </div>
        <div class="flex space-x-3">
          <button @click="clearSignature" class="flex-1 bg-white dark:bg-[#131722] border border-gray-200 dark:border-[#363c4e] border border-gray-200 dark:border-[#2b3139]-gray-300 text-gray-700 dark:text-gray-200 py-3 rounded-lg font-bold text-sm hover:bg-gray-50 dark:hover:bg-[#181c27] dark:bg-[#181c27] transition-colors">{{ localeStore.t('resignQuote') }}</button>
          <button @click="submitSignature" :disabled="signSubmitting" class="flex-1 bg-[#8cc63f] text-white py-3 rounded-lg font-bold text-sm hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200 disabled:opacity-50">
            {{ signSubmitting ? `${localeStore.t('submittingText')}...` : localeStore.t('submitSignature') }}
          </button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="showCreditLoan" :title="localeStore.t('creditLoan')" width="500px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="bg-gray-50 dark:bg-[#181c27] p-6 rounded-lg text-center mb-6 border border-gray-100 dark:border-[#2b3139] shadow-inner relative overflow-hidden">
        <div class="absolute inset-0 bg-gradient-to-br from-white to-transparent opacity-50"></div>
        <div class="relative z-10">
          <div class="flex justify-between items-center mb-3">
            <div class="text-gray-600 dark:text-gray-300 font-medium text-base">{{ localeStore.t('enjoyLoanService') }}</div>
            <button @click="openLoanRecords" class="text-sm text-[#8cc63f] hover:underline font-medium">{{ localeStore.t('loanRecords') }}</button>
          </div>
          <button 
            @click="!isKycVerified && (showPersonalInfoModal = true)"
            class="inline-flex items-center px-4 py-1.5 rounded-full text-sm font-bold shadow-sm transition-colors cursor-pointer"
            :class="isKycVerified ? 'text-[#8cc63f] bg-green-100' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 bg-gray-200 dark:bg-[#363c4e] hover:bg-gray-300'"
          >
            <el-icon v-if="isKycVerified" class="mr-1 text-lg"><Check /></el-icon>
            {{ isKycVerified ? localeStore.t('verifiedStatus') : localeStore.t('unverifiedStatus') }}
          </button>
        </div>
      </div>
      <div class="space-y-5 px-2">
        <div class="flex flex-col space-y-1">
          <div class="flex items-center border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-3 focus-within:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus-within:ring-1 focus-within:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]">
            <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 w-16 font-medium">{{ localeStore.t('amountShort') }}</span>
            <input v-model="loanAmount" type="number" class="flex-1 outline-none text-gray-800 dark:text-gray-100 font-mono text-base bg-transparent" :placeholder="currentLoanSetting ? ` (${formatMoney(currentLoanSetting.minAmount)} - ${formatMoney(currentLoanSetting.maxAmount)})` : localeStore.t('pleaseEnterAmount')" />
            <button @click="setMaxLoanAmount" class="text-white text-xs ml-2 bg-[#8cc63f] px-3 py-1.5 rounded font-bold hover:bg-[#7ab036] transition-colors shadow-sm">{{ localeStore.t('maxButton') }}</button>
          </div>
          <div v-if="currentLoanSetting" class="flex justify-between text-xs text-gray-400 dark:text-gray-500 px-1">
            <span>{{ localeStore.t('minimum') }} {{ formatMoney(currentLoanSetting.minAmount) }}</span>
            <span>{{ localeStore.t('maximum') }} {{ formatMoney(currentLoanSetting.maxAmount) }}</span>
          </div>
        </div>
        <div class="flex items-center border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-3 focus-within:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus-within:ring-1 focus-within:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]">
          <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 w-16 font-medium">{{ localeStore.t('loanTermShort') }}</span>
          <select v-model="selectedLoanSettingId" class="flex-1 outline-none bg-transparent text-gray-800 dark:text-gray-100 font-medium text-base cursor-pointer appearance-none">
            <option v-for="setting in loanSettings" :key="setting.id" :value="setting.id">{{ setting.days }}{{ localeStore.t('daysUnit') }}</option>
          </select>
        </div>
        <div class="text-sm text-gray-600 dark:text-gray-300 space-y-3 mt-6 bg-gray-50 dark:bg-[#181c27] p-5 rounded-lg border border-gray-100 dark:border-[#2b3139]">
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('dailyInterestRate') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ currentLoanSetting ? currentLoanSetting.dailyRate : 0 }}%</span></div>
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('freeInterestDays') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ currentLoanSetting ? currentLoanSetting.freeDays : 0 }}</span></div>
          <div class="flex justify-between items-center"><span class="font-medium">{{ localeStore.t('totalInterest') }}</span><span class="font-bold text-[#ff4d4f] text-base">{{ totalLoanInterest }}</span></div>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="submitLoan" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('borrowNow') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showLoanRecordsModal" :title="localeStore.t('loanRecords')" width="600px" class="custom-dialog rounded-xl">
      <div class="space-y-4 h-[400px] overflow-y-auto custom-scrollbar p-2">
        <div v-if="userLoanRecords.length === 0" class="h-full flex items-center justify-center">
          <el-empty :description="localeStore.t('noLoanRecords2')" />
        </div>
        <div v-else v-for="loan in userLoanRecords" :key="loan.id" class="border-l border-gray-200 dark:border-[#363c4e]-4 rounded-xl p-5 bg-white dark:bg-[#131722] shadow-sm border-gray-100 dark:border-[#2b3139] hover:shadow-md transition-shadow relative" :class="loan.status === 'COMPLETED' ? 'border-l border-gray-200 dark:border-[#2b3139]-gray-400' : (loan.status === 'REJECTED' ? 'border-l border-gray-200 dark:border-[#2b3139]-red-500' : 'border-l border-gray-200 dark:border-[#2b3139]-[#8cc63f]')">
          <div class="flex justify-between items-center mb-4 border-b border-gray-200 dark:border-[#2b3139] pb-3">
            <div class="font-bold text-gray-800 dark:text-gray-100 text-lg"># {{ loan.id }}</div>
            <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ loan.createdAt }}</div>
          </div>
          
          <div class="space-y-2 text-sm text-gray-600 dark:text-gray-300">
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('amountShort') }}</span><span class="font-medium text-gray-800 dark:text-gray-100">{{ formatMoney(loan.amount) }}</span></div>
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('loanTermShort') }}</span><span class="font-medium text-gray-800 dark:text-gray-100">{{ loan.days }}{{ localeStore.t('daysUnit') }}</span></div>
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('totalInterest') }}</span><span class="font-medium text-gray-800 dark:text-gray-100">{{ formatMoney(loan.totalInterest) }}</span></div>
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('dailyInterestRate') }}</span><span class="font-medium text-gray-800 dark:text-gray-100">{{ loan.dailyRate }}%</span></div>
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('needRepay') }}</span><span class="font-bold text-orange-500">{{ formatMoney(loan.repaymentAmount) }}</span></div>
            <div class="flex justify-between items-center"><span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('repayDate') }}</span><span class="font-medium text-gray-800 dark:text-gray-100">{{ loan.repaymentDate || '-' }}</span></div>
          </div>

          <div class="flex space-x-3 mt-5 pt-4 border-t border-gray-200 dark:border-[#2b3139]">
            <button @click="viewLoanContract(loan.id)" class="flex-1 py-2.5 rounded-lg font-bold text-sm transition-colors border border-gray-200 dark:border-[#2b3139]" :class="loan.contractSigned ? 'bg-gray-50 dark:bg-[#181c27] border-gray-200 dark:border-[#2b3139] text-gray-500 dark:text-gray-400 dark:text-gray-500' : 'bg-[#8cc63f] border-[#8cc63f] text-white hover:bg-[#7ab036]'">
              {{ loan.contractSigned ? localeStore.t('alreadySignedText') : localeStore.t('viewAndSignAgreement') }}
            </button>
            <button v-if="loan.status === 'APPROVED' || loan.status === 'OVERDUE'" @click="repayLoan(loan.id)" class="flex-1 bg-orange-400 text-white py-2.5 rounded-lg font-bold text-sm hover:bg-orange-500 transition-colors shadow-sm">{{ localeStore.t('repayImmediately') }}</button>
            <div v-else-if="loan.status === 'PENDING' || loan.status === 'SIGNED'" class="flex-1 py-2.5 text-center text-orange-400 font-bold text-sm bg-orange-50 rounded-lg">{{ localeStore.t('pendingAudit') }}</div>
            <div v-else-if="loan.status === 'COMPLETED'" class="flex-1 py-2.5 text-center text-gray-400 dark:text-gray-500 font-bold text-sm bg-gray-50 dark:bg-[#181c27] rounded-lg">{{ localeStore.t('cleared') }}</div>
            <div v-else-if="loan.status === 'REJECTED'" class="flex-1 py-2.5 text-center text-red-500 font-bold text-sm bg-red-50 rounded-lg">{{ localeStore.t('rejected') }}</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="showWealth" :title="localeStore.t('financialManagement')" width="800px" class="custom-dialog rounded-xl">
      <el-tabs v-model="wealthTab" class="px-2">
        <el-tab-pane :label="localeStore.t('miningMachineList')" name="mining">
          <div class="space-y-4 py-2 h-[400px] overflow-y-auto custom-scrollbar pr-2">
            <div class="border border-gray-200 dark:border-[#2b3139] rounded-lg p-5 flex justify-between items-center hover:shadow-md transition-shadow bg-white dark:bg-[#131722]" v-for="product in financialProducts" :key="product.id">
              <div class="flex items-center space-x-5">
                <img :src="getImageUrl(product.imageUrl)" class="w-14 h-14 rounded-full shadow-inner object-contain bg-gray-50 dark:bg-[#181c27] p-1" />
                <div>
                  <div class="font-bold text-lg text-gray-800 dark:text-gray-100 mb-1">{{ product.name }}</div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-0.5">{{ localeStore.t('estDailyYield') }}: <span class="font-bold text-gray-700 dark:text-gray-200">{{ Number(product.dailyYieldRate).toFixed(2) }}%</span></div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('lockRent') }}: <span class="font-mono text-gray-700 dark:text-gray-200 font-medium">{{ Number(product.rentalFee).toFixed(2) }}</span></div>
                </div>
              </div>
              <button @click="showFinancialPurchaseModal(product)" class="bg-[#8cc63f] text-white px-8 py-2.5 rounded-lg font-bold hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200">{{ localeStore.t('subscribe') }}</button>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="localeStore.t('subscribeList')" name="purchased">
          <div class="space-y-4 py-2 h-[400px] overflow-y-auto custom-scrollbar pr-2">
            <div v-if="purchasedFinancialOrders.length === 0" class="h-full flex items-center justify-center">
              <el-empty :description="localeStore.t('noDataText')" />
            </div>
            <template v-else>
              <div v-for="order in purchasedFinancialOrders" :key="order.id" class="border border-gray-200 dark:border-[#2b3139] rounded-lg p-5 hover:shadow-md transition-shadow bg-white dark:bg-[#131722] relative overflow-hidden">
                <div class="absolute right-0 top-0 w-2 h-full" :class="order.status === 'IN_PROGRESS' ? 'bg-[#8cc63f]' : (order.status === 'REDEEMED' ? 'bg-orange-300' : 'bg-gray-300')"></div>
                <div class="flex justify-between items-center mb-3 border-b border-gray-200 dark:border-[#2b3139] pb-3">
                  <div class="font-bold text-lg text-gray-800 dark:text-gray-100">{{ order.productName }}</div>
                  <div class="text-sm font-medium px-3 py-1 rounded-full" :class="order.status === 'IN_PROGRESS' ? 'bg-green-50 text-[#8cc63f]' : (order.status === 'REDEEMED' ? 'bg-orange-50 text-orange-400' : 'bg-gray-100 dark:bg-[#2b3139] text-gray-500 dark:text-gray-400 dark:text-gray-500')">
                      {{ order.status === 'IN_PROGRESS' ? localeStore.t('inProgress') : (order.status === 'COMPLETED' ? localeStore.t('completedText') : localeStore.t('alreadyRedeemed')) }}
                    </div>
                </div>
                <div class="grid grid-cols-2 gap-y-3 text-sm text-gray-600 dark:text-gray-300">
                  <div>{{ localeStore.t('subscribe') }}{{ localeStore.t('quantityText') }}: <span class="font-bold text-gray-800 dark:text-gray-100 font-mono block mt-1">{{ Number(order.purchaseAmount).toFixed(2) }}</span></div>
                  <div>{{ localeStore.t('currency') }}: <span class="font-bold text-gray-800 dark:text-gray-100 block mt-1">{{ order.currency }}</span></div>
                  <div>{{ localeStore.t('estDailyYield') }}: <span class="font-bold text-[#8cc63f] font-mono block mt-1">{{ Number(order.dailyYield).toFixed(2) }}</span></div>
                  <div>{{ localeStore.t('subscribe') }}{{ localeStore.t('timeText') }}: <span class="font-medium text-gray-800 dark:text-gray-100 font-mono block mt-1">{{ order.purchaseTime }}</span></div>
                  <div class="col-span-2">{{ localeStore.t('estTotalYield') }}: <span class="font-bold text-[#8cc63f] font-mono block mt-1">{{ Number(order.totalYield).toFixed(2) }}</span></div>
                </div>
                <div class="flex space-x-3 mt-4 pt-3 border-t border-gray-200 dark:border-[#2b3139]">
                  <button @click="earlyRedeemOrder(order)" :disabled="order.status !== 'IN_PROGRESS'" :class="['flex-1 py-2.5 rounded-lg font-bold transition-colors shadow-sm', order.status === 'IN_PROGRESS' ? 'bg-[#e8f5e9] text-[#8cc63f] hover:bg-[#d4edda]' : 'bg-gray-100 dark:bg-[#2b3139] text-gray-400 dark:text-gray-500 cursor-not-allowed']">{{ localeStore.t('earlyRedemption') }}</button>
                  <button @click="showYieldListModal(order)" class="flex-1 bg-[#8cc63f] text-white py-2.5 rounded-lg font-bold hover:bg-[#7ab036] transition-colors shadow-sm">{{ localeStore.t('viewYieldList') }}</button>
                </div>
              </div>
            </template>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <el-dialog v-model="showYieldList" :title="localeStore.t('yieldList')" width="600px" class="custom-dialog rounded-xl">
      <div v-if="currentYieldStats" class="grid grid-cols-2 gap-4 mb-4">
        <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-4 text-center border border-gray-100 dark:border-[#2b3139] shadow-sm">
          <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('totalYieldText') }}</div>
          <div class="font-bold text-xl text-gray-800 dark:text-gray-100">{{ currentYieldStats.totalYield ? Number(currentYieldStats.totalYield).toFixed(2) : '0.00' }}</div>
        </div>
        <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-4 text-center border border-gray-100 dark:border-[#2b3139] shadow-sm">
          <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('paidYieldText') }}</div>
          <div class="font-bold text-xl text-[#8cc63f]">{{ currentYieldStats.paidYield ? Number(currentYieldStats.paidYield).toFixed(2) : '0.00' }}</div>
        </div>
        <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-4 text-center border border-gray-100 dark:border-[#2b3139] shadow-sm">
          <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('pendingYieldText') }}</div>
          <div class="font-bold text-xl text-orange-400">{{ currentYieldStats.pendingYield ? Number(currentYieldStats.pendingYield).toFixed(2) : '0.00' }}</div>
        </div>
        <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-4 text-center border border-gray-100 dark:border-[#2b3139] shadow-sm">
          <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('yieldDaysText') }}</div>
          <div class="font-bold text-xl text-gray-800 dark:text-gray-100">{{ currentYieldStats.recordCount || 0 }}</div>
        </div>
      </div>
      <div class="space-y-4 h-[400px] overflow-y-auto custom-scrollbar pr-2 pb-2">
        <div v-if="currentYieldList.length === 0" class="h-full flex items-center justify-center">
          <el-empty :description="localeStore.t('noYieldRecordsText')" />
        </div>
        <div v-else v-for="yieldRecord in currentYieldList" :key="yieldRecord.id" class="border border-gray-200 dark:border-[#2b3139] rounded-lg p-4 bg-white dark:bg-[#131722] shadow-sm hover:shadow-md transition-shadow">
          <div class="flex justify-between items-center mb-3 border-b border-gray-200 dark:border-[#2b3139] pb-2">
            <div class="font-bold text-gray-800 dark:text-gray-100 text-base">{{ yieldRecord.yieldDate }}</div>
            <div class="text-xs px-2 py-1 rounded-full font-medium" :class="yieldRecord.status === 'PAID' ? 'bg-green-50 text-[#8cc63f]' : 'bg-orange-50 text-orange-400'">
              {{ yieldRecord.status === 'PAID' ? localeStore.t('paidYieldText') : localeStore.t('pendingYieldText') }}
            </div>
          </div>
          <div class="space-y-2 text-sm text-gray-600 dark:text-gray-300">
            <div class="flex justify-between"><span>{{ localeStore.t('dailyProfit') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ Number(yieldRecord.dailyYield).toFixed(2) }}</span></div>
            <div class="flex justify-between"><span>{{ localeStore.t('totalYield') }}</span><span class="font-bold text-gray-800 dark:text-gray-100">{{ Number(yieldRecord.cumulativeYield).toFixed(2) }}</span></div>
            <div class="flex justify-between" v-if="yieldRecord.paidAt"><span>{{ localeStore.t('issueText') }}{{ localeStore.t('timeText') }}</span><span class="text-gray-400 dark:text-gray-500">{{ yieldRecord.paidAt }}</span></div>
          </div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="showUserCenter" :title="localeStore.t('tabbarPersonalCenter')" width="900px" class="custom-dialog rounded-xl p-0 overflow-hidden">
      <div class="flex h-[550px] -mx-4 -mb-4 -mt-2">
        <div class="w-56 border-r border-gray-200 dark:border-[#2b3139] overflow-y-auto bg-gray-50 dark:bg-[#181c27]/50 py-4 custom-scrollbar">
          <div v-for="item in userMenus" :key="item.id" 
               @click="activeUserMenu = item.id"
               :class="['px-6 py-3.5 cursor-pointer text-sm font-medium transition-colors relative', activeUserMenu === item.id ? 'bg-white dark:bg-[#131722] text-[#8cc63f] shadow-[0_2px_8px_rgba(0,0,0,0.04)] z-10' : 'hover:bg-gray-100 dark:hover:bg-[#2b3139] dark:bg-[#2b3139] text-gray-600 dark:text-gray-300']">
            <div class="absolute left-0 top-0 bottom-0 w-1 bg-[#8cc63f] transition-opacity" :class="activeUserMenu === item.id ? 'opacity-100' : 'opacity-0'"></div>
            {{ item.name }}
          </div>
        </div>
        <div class="flex-1 p-8 overflow-y-auto bg-white dark:bg-[#131722] custom-scrollbar relative">
          <div v-if="activeUserMenu === 'assets'" class="max-w-2xl mx-auto">
            <div class="text-center mb-10 bg-gray-50 dark:bg-[#181c27] p-8 rounded-2xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden">
              <div class="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-[#8cc63f] to-[#aae061]"></div>
              <div class="inline-block bg-white dark:bg-[#131722] px-4 py-1.5 rounded-full text-gray-500 dark:text-gray-400 dark:text-gray-500 text-sm font-medium mb-4 shadow-sm border border-gray-100 dark:border-[#2b3139]">UID:< {{ auth.user?.id || '8959285729' }}</div>
              <div class="text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium mb-2">{{ localeStore.t('totalAssetsEquivalent') }}</div>
              <div class="text-4xl text-[#8cc63f] font-bold font-mono tracking-tight">${{ totalAsset.toFixed(2) }}</div>
            </div>
            <div class="space-y-4">
              <div class="border border-gray-200 dark:border-[#2b3139] rounded-xl p-5 flex justify-between items-center cursor-pointer hover:border-[#8cc63f] hover:shadow-md transition-all group bg-white dark:bg-[#131722]">
                <div>
                  <div class="font-bold text-gray-800 dark:text-gray-100 mb-1.5 text-base">{{ localeStore.t('fundAccount') }}</div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium">{{ localeStore.t('balance') }}<span class="font-mono text-gray-700 dark:text-gray-200 ml-1">{{ walletBalance.toFixed(2) }}</span></div>
                </div>
                <div class="flex items-center space-x-6">
                  <div class="text-right">
                    <div class="text-xs text-gray-400 dark:text-gray-500 mb-1 font-medium">{{ localeStore.t('frozenAmount') }}</div>
                    <div class="text-sm font-mono font-medium text-gray-700 dark:text-gray-200">{{ walletFrozen.toFixed(2) }}</div>
                  </div>
                  <div class="w-8 h-8 rounded-full bg-gray-50 dark:bg-[#181c27] flex items-center justify-center group-hover:bg-green-50 transition-colors">
                    <el-icon class="text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f] transition-colors"><ArrowRight /></el-icon>
                  </div>
                </div>
              </div>
              <div class="border border-gray-200 dark:border-[#2b3139] rounded-xl p-5 flex justify-between items-center cursor-pointer hover:border-[#8cc63f] hover:shadow-md transition-all group bg-white dark:bg-[#131722]">
                <div>
                  <div class="font-bold text-gray-800 dark:text-gray-100 mb-1.5 text-base">{{ localeStore.t('optionsAccount') }}</div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium">{{ localeStore.t('balance') }}<span class="font-mono text-gray-700 dark:text-gray-200 ml-1">{{ optionBalance.toFixed(2) }}</span></div>
                </div>
                <div class="flex items-center space-x-6">
                  <div class="text-right">
                    <div class="text-xs text-gray-400 dark:text-gray-500 mb-1 font-medium">{{ localeStore.t('frozenAmount') }}</div>
                    <div class="text-sm font-mono font-medium text-gray-700 dark:text-gray-200">{{ optionFrozen.toFixed(2) }}</div>
                  </div>
                  <div class="w-8 h-8 rounded-full bg-gray-50 dark:bg-[#181c27] flex items-center justify-center group-hover:bg-green-50 transition-colors">
                    <el-icon class="text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f] transition-colors"><ArrowRight /></el-icon>
                  </div>
                </div>
              </div>
              <div class="border border-gray-200 dark:border-[#2b3139] rounded-xl p-5 flex justify-between items-center cursor-pointer hover:border-[#8cc63f] hover:shadow-md transition-all group bg-white dark:bg-[#131722]">
                <div>
                  <div class="font-bold text-gray-800 dark:text-gray-100 mb-1.5 text-base">{{ localeStore.t('contractAccount') }}</div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 font-medium">{{ localeStore.t('balance') }}<span class="font-mono text-gray-700 dark:text-gray-200 ml-1">{{ contractBalance.toFixed(2) }}</span></div>
                </div>
                <div class="flex items-center space-x-6">
                  <div class="text-right">
                    <div class="text-xs text-gray-400 dark:text-gray-500 mb-1 font-medium">{{ localeStore.t('frozenAmount') }}</div>
                    <div class="text-sm font-mono font-medium text-gray-700 dark:text-gray-200">{{ contractFrozen.toFixed(2) }}</div>
                  </div>
                  <div class="w-8 h-8 rounded-full bg-gray-50 dark:bg-[#181c27] flex items-center justify-center group-hover:bg-green-50 transition-colors">
                    <el-icon class="text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f] transition-colors"><ArrowRight /></el-icon>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-else-if="activeUserMenu === 'deposit'" class="max-w-2xl mx-auto pb-6">
            <div class="mb-8">
              <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-2">{{ localeStore.t('recharge') }}</h2>
              <p class="text-gray-500 dark:text-gray-400 dark:text-gray-500 text-sm">{{ localeStore.t('selectNetworkGetAddress') }}</p>
            </div>
            
            <div class="bg-gray-100 dark:bg-[#2b3139]/80 p-1.5 rounded-xl flex space-x-2 mb-8">
              <button @click="depositTab = 'digital'" :class="['flex-1 py-2.5 rounded-lg font-bold text-sm transition-all', depositTab === 'digital' ? 'bg-white dark:bg-[#131722] text-gray-800 dark:text-gray-100 shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('digitalCurrencyLabel') }}</button>
              <button @click="depositTab = 'bank'" :class="['flex-1 py-2.5 rounded-lg font-bold text-sm transition-all', depositTab === 'bank' ? 'bg-[#8cc63f] text-white shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('bankCardLabel') }}</button>
            </div>

            <div v-if="depositTab === 'digital'" class="space-y-6">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('selectNetworkText') }}</label>
                <div class="grid grid-cols-2 gap-3">
                  <div v-for="setting in depositSettings" :key="setting.id"
                       @click="selectedDepositSetting = setting"
                       :class="['border border-gray-200 dark:border-[#2b3139] rounded-xl p-4 cursor-pointer transition-all', selectedDepositSetting?.id === setting.id ? 'border-[#8cc63f] bg-green-50/50 shadow-sm' : 'border-gray-200 dark:border-[#2b3139] hover:border-green-300']">
                    <div class="font-bold text-gray-800 dark:text-gray-100">{{ setting.network }}</div>
                  </div>
                </div>
              </div>

              <div v-if="selectedDepositSetting" class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-6 border border-gray-100 dark:border-[#2b3139] text-center relative overflow-hidden">
                <div class="absolute top-0 left-0 w-full h-1 bg-[#8cc63f]"></div>
                <div class="mb-4">
                  <vue-qrcode :value="selectedDepositSetting.address" :options="{ width: 160, color: { dark: '#1f2937' } }" class="mx-auto p-2 bg-white dark:bg-[#131722] rounded-lg shadow-sm border border-gray-100 dark:border-[#2b3139]" />
                </div>
                <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('depositAddressText') }}</div>
                <div class="font-mono font-medium text-gray-800 dark:text-gray-100 bg-white dark:bg-[#131722] px-4 py-2 rounded border border-gray-200 dark:border-[#2b3139] inline-block mb-3 select-all">{{ selectedDepositSetting.address }}</div>
                <div class="text-xs text-gray-400 dark:text-gray-500">{{ localeStore.t('onlySupportVia') }} {{ selectedDepositSetting.network }} {{ localeStore.t('networkDepositText') }}</div>
              </div>
            </div>
            
            <div v-if="depositTab === 'bank'" class="space-y-6">
              <div v-if="bankSetting" class="bg-white dark:bg-[#131722] rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm p-6 space-y-4 relative overflow-hidden">
                <div class="absolute top-0 left-0 w-1 h-full bg-[#8cc63f]"></div>
                <div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('openingBankText') }}</div>
                  <div class="font-bold text-gray-800 dark:text-gray-100 bg-gray-50 dark:bg-[#181c27] px-4 py-3 rounded-lg">{{ bankSetting.bankName }}</div>
                </div>
                <div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('bankCardNoText') }}</div>
                  <div class="font-bold font-mono text-gray-800 dark:text-gray-100 bg-gray-50 dark:bg-[#181c27] px-4 py-3 rounded-lg flex justify-between items-center">
                    <span>{{ bankSetting.bankAccount }}</span>
                    <el-icon class="text-[#8cc63f] cursor-pointer hover:text-[#7ab036] text-lg"><DocumentCopy /></el-icon>
                  </div>
                </div>
                <div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('accountNameText') }}</div>
                  <div class="font-bold text-gray-800 dark:text-gray-100 bg-gray-50 dark:bg-[#181c27] px-4 py-3 rounded-lg">{{ bankSetting.accountName }}</div>
                </div>
              </div>
              <div v-else class="text-center py-10 bg-gray-50 dark:bg-[#181c27] rounded-xl border border-gray-100 dark:border-[#2b3139]">
                <el-icon class="text-4xl text-gray-300 mb-2"><Box /></el-icon>
                <div class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('notConfigured') }}{{ localeStore.t('bankCardDeposit') }}{{ localeStore.t('channel') }}</div>
              </div>
            </div>

            <div v-if="(depositTab === 'digital' && selectedDepositSetting) || (depositTab === 'bank' && bankSetting)" class="space-y-4 mt-6">
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('depositAmount') }}</label>
                <el-input v-model="depositForm.amount" type="number" :placeholder="localeStore.t('enterDepositAmount')" class="custom-input">
                  <template #append>{{ depositTab === 'digital' ? (selectedDepositSetting?.network ? selectedDepositSetting.network.split('-')[0] : 'USDT') : 'CNY' }}</template>
                </el-input>
              </div>
              
              <div>
                <label class="block text-sm font-medium text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('uploadPaymentVoucher') }}</label>
                <div class="w-full border-2 border-dashed border-gray-200 dark:border-[#2b3139] rounded-lg hover:border-[#8cc63f] transition-colors cursor-pointer overflow-hidden bg-gray-50 dark:bg-[#181c27] relative group">
                  <input 
                    ref="depositProofInput"
                    type="file" 
                    accept="image/*" 
                    @change="(e) => handleImageUpload(e, 'deposit')"
                    class="hidden"
                  />
                  <div 
                    v-if="depositForm.proofImage" 
                    class="relative h-[160px] w-full"
                    @click="triggerDepositProofUpload"
                  >
                    <img :src="getImageUrl(depositForm.proofImage)" class="w-full h-full object-contain" />
                    <div class="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                      <span class="text-white text-sm">{{ localeStore.t('clickReupload') }}</span>
                    </div>
                  </div>
                  <div 
                    v-else 
                    class="h-[160px] flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f] transition-colors"
                    @click="triggerDepositProofUpload"
                  >
                    <el-icon class="text-4xl mb-2"><UploadFilled /></el-icon>
                    <div class="text-sm">{{ localeStore.t('clickUploadVoucher') }}</div>
                  </div>
                </div>
              </div>
              
              <button @click="submitDeposit" class="w-full bg-[#8cc63f] text-white py-3 rounded-lg font-bold hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200 mt-4">{{ localeStore.t('submitDepositApplication') }}</button>
            </div>
            
            <div class="mt-8 bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm">
              <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100 mb-4">{{ localeStore.t('depositRecords') }}</h3>
              <div v-if="depositRecords.length === 0" class="text-center py-6 text-gray-400 dark:text-gray-500">
                <el-empty :description="localeStore.t('noDepositRecords2')" :image-size="60"></el-empty>
              </div>
              <div v-else class="space-y-4">
                <div v-for="record in depositRecords" :key="record.id" class="border border-gray-200 dark:border-[#2b3139]-b border border-gray-200 dark:border-[#2b3139]-gray-50 pb-4 last:border border-gray-200 dark:border-[#2b3139]-0 last:pb-0">
                  <div class="flex justify-between items-start mb-2">
                    <div class="font-bold text-gray-800 dark:text-gray-100 text-base">{{ localeStore.t('quantityText') }}: {{ Number(record.amount).toFixed(2) }} {{ record.type === 'digital' ? (record.network ? record.network.split('-')[0] : 'USDT') : 'CNY' }}</div>
                    <div class="text-xs px-2 py-1 rounded-full font-medium" :class="record.status === 'COMPLETED' ? 'bg-green-50 text-[#8cc63f]' : (record.status === 'REJECTED' ? 'bg-red-50 text-red-500' : 'bg-orange-50 text-orange-400')">
                      {{ record.status === 'COMPLETED' ? 'completedText' : (record.status === 'REJECTED' ? localeStore.t('rejectedText') : localeStore.t('reviewingText')) }}
                    </div>
                  </div>
                  <div class="text-sm text-gray-600 dark:text-gray-300 mb-1">
                    {{ record.type === 'digital' ? localeStore.t('digitalCurrencyDeposit') : localeStore.t('bankCardDeposit2') }}
                  </div>
                  <div v-if="record.status === 'REJECTED' && record.remark" class="text-xs text-red-500 bg-red-50 p-2 rounded mb-1">
                    {{ localeStore.t('rejectReasonText') }}: {{ record.remark }}
                  </div>
                  <div class="text-xs text-gray-400 dark:text-gray-500">{{ record.createdAt }}</div>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'withdraw'" class="max-w-2xl mx-auto pb-6">
            <div class="bg-gray-100 dark:bg-[#2b3139]/80 p-1.5 rounded-xl flex space-x-2 mb-8">
              <button @click="withdrawTab = 'digital'" :class="['flex-1 py-2.5 rounded-lg font-bold text-sm transition-all', withdrawTab === 'digital' ? 'bg-white dark:bg-[#131722] text-[#8cc63f] shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('digitalCurrencyLabel') }}</button>
              <button @click="withdrawTab = 'bank'" :class="['flex-1 py-2.5 rounded-lg font-bold text-sm transition-all', withdrawTab === 'bank' ? 'bg-white dark:bg-[#131722] text-[#8cc63f] shadow-sm' : 'text-gray-500 dark:text-gray-400 dark:text-gray-500 hover:text-gray-700 dark:hover:text-gray-200 dark:text-gray-200']">{{ localeStore.t('bankCardLabel') }}</button>
            </div>

            <div v-if="withdrawTab === 'digital'" class="space-y-5">
              <div class="bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm space-y-5 relative overflow-hidden">
                <div class="absolute top-0 left-0 w-1 h-full bg-[#8cc63f]"></div>
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('currency') }}</div>
                  <div class="relative">
                    <select v-model="withdrawForm.currency" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
                      <option value="" disabled selected hidden>{{ localeStore.t('pleaseText') }}选择{{ localeStore.t('currency') }}</option>
                      <option v-for="c in availableCurrencies" :key="c" :value="c">{{ c }}</option>
                    </select>
                    <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowRight /></el-icon>
                  </div>
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('withdrawAddressText') }}</div>
                  <div class="relative">
                    <select v-model="withdrawForm.address" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
                      <option value="" disabled selected hidden>{{ localeStore.t('pleaseSelectWithdrawAddress') }}</option>
                      <option v-for="addr in userDigitalAddresses" :key="addr.id" :value="addr.address">{{ addr.address }} ({{ addr.network }})</option>
                    </select>
                    <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowRight /></el-icon>
                  </div>
                  <div v-if="userDigitalAddresses.length === 0" class="text-xs text-red-400 mt-1">{{ localeStore.t('noneText') }}{{ localeStore.t('boundText') }}{{ localeStore.t('withdrawAddressText') }}，{{ localeStore.t('pleaseContactFirst') }}{{ localeStore.t('customerServiceText') }}{{ localeStore.t('orBindOnMobile') }}</div>
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('amountLabel') }}</div>
                  <input v-model="withdrawForm.amount" type="number" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('amountLabel')" />
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('remarkText') }}{{ localeStore.t('currency') }}{{ localeStore.t('nameText2') }}</div>
                  <input v-model="withdrawForm.remark" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('remarkLabel')" />
                </div>
              </div>
              
              <div class="bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm space-y-3">
                <div class="flex justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('handlingFee') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-medium">0</span>
                </div>
                <div class="flex justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('expectedArrival') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-medium">{{ withdrawForm.amount || 0 }}</span>
                </div>
                <div class="flex justify-between text-sm pt-2 border border-gray-200 dark:border-[#2b3139]-t border border-gray-200 dark:border-[#2b3139]-gray-50">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('balance') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-bold">{{ walletBalance.toFixed(2) }} {{ withdrawForm.currency || 'USD' }}</span>
                </div>
              </div>
              
              <button @click="submitWithdraw" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200 text-lg">{{ localeStore.t('withdrawCoin') }}</button>
            </div>
            
            <!-- 银行卡提币 -->
            <div v-if="withdrawTab === 'bank'" class="space-y-5">
              <div class="bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm space-y-5 relative overflow-hidden">
                <div class="absolute top-0 left-0 w-1 h-full bg-[#8cc63f]"></div>
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('currency') }}</div>
                  <div class="relative">
                    <select v-model="withdrawForm.currency" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
                      <option value="" disabled selected hidden>{{ localeStore.t('pleaseText') }}选择{{ localeStore.t('currency') }}</option>
                      <option v-for="c in availableCurrencies" :key="c" :value="c">{{ c }}</option>
                    </select>
                    <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowRight /></el-icon>
                  </div>
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('payeeAccount') }}</div>
                  <div class="relative">
                    <select v-model="withdrawForm.address" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
                      <option value="" disabled selected hidden>{{ localeStore.t('pleaseSelectPayeeAccount') }}</option>
                      <option v-for="card in userBankCards" :key="card.id" :value="card.recipientAccount">{{ card.bankName }} - {{ card.recipientAccount }}</option>
                    </select>
                    <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowRight /></el-icon>
                  </div>
                  <div v-if="userBankCards.length === 0" class="text-xs text-red-400 mt-1">{{ localeStore.t('noneText') }}{{ localeStore.t('boundText') }}{{ localeStore.t('bankCardText2') }}，{{ localeStore.t('pleaseContactFirst') }}{{ localeStore.t('customerServiceText') }}{{ localeStore.t('orBindOnMobile') }}</div>
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('amountLabel') }}</div>
                  <input v-model="withdrawForm.amount" type="number" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('amountLabel')" />
                </div>
                
                <div>
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2">{{ localeStore.t('remarkText') }}{{ localeStore.t('currency') }}{{ localeStore.t('nameText2') }}</div>
                  <input v-model="withdrawForm.remark" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('remarkLabel')" />
                </div>
              </div>
              
              <div class="bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm space-y-3">
                <div class="flex justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('handlingFee') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-medium">0</span>
                </div>
                <div class="flex justify-between text-sm">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('expectedArrival') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-medium">{{ withdrawForm.amount || 0 }}</span>
                </div>
                <div class="flex justify-between text-sm pt-2 border border-gray-200 dark:border-[#2b3139]-t border border-gray-200 dark:border-[#2b3139]-gray-50">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('balance') }}</span>
                  <span class="text-gray-800 dark:text-gray-100 font-bold">{{ walletBalance.toFixed(2) }} {{ withdrawForm.currency || 'USD' }}</span>
                </div>
              </div>
              
              <button @click="submitWithdraw" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200 text-lg">{{ localeStore.t('withdrawCoin') }}</button>
            </div>
            
            <div class="mt-8 bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm">
              <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100 mb-4">{{ localeStore.t('withdrawRecords') }}</h3>
              <div v-if="withdrawRecords.length === 0" class="text-center py-6 text-gray-400 dark:text-gray-500">
                <el-empty :description="localeStore.t('noWithdrawRecords2')" :image-size="60"></el-empty>
              </div>
              <div v-else class="space-y-4">
                <div v-for="record in withdrawRecords" :key="record.id" class="border border-gray-200 dark:border-[#2b3139]-b border border-gray-200 dark:border-[#2b3139]-gray-50 pb-4 last:border border-gray-200 dark:border-[#2b3139]-0 last:pb-0">
                  <div class="flex justify-between items-start mb-2">
                    <div class="font-bold text-gray-800 dark:text-gray-100 text-base">{{ localeStore.t('quantityText') }}: {{ Number(record.amount).toFixed(2) }}</div>
                    <div class="text-xs px-2 py-1 rounded-full font-medium" :class="record.status === 'COMPLETED' ? 'bg-green-50 text-[#8cc63f]' : (record.status === 'REJECTED' ? 'bg-red-50 text-red-500' : 'bg-orange-50 text-orange-400')">
                      {{ record.status === 'COMPLETED' ? 'completedText' : (record.status === 'REJECTED' ? localeStore.t('rejectedText') : (record.status === 'APPROVED' ? localeStore.t('passed') : localeStore.t('reviewingText'))) }}
                    </div>
                  </div>
                  <div class="text-sm text-gray-600 dark:text-gray-300 mb-1">
                    {{ record.type === 'digital' ? localeStore.t('cryptoWithdraw') : localeStore.t('bankCardWithdraw') }} - {{ localeStore.t('arrivalText') }}: {{ Number(record.actualAmount).toFixed(2) }}
                  </div>
                  <div v-if="record.status === 'REJECTED' && record.reviewRemark" class="text-xs text-red-500 bg-red-50 p-2 rounded mb-1">
                    {{ localeStore.t('rejectReasonText') }}: {{ record.reviewRemark }}
                  </div>
                  <div class="text-xs text-gray-400 dark:text-gray-500">{{ record.createdAt }}</div>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'transfer'" class="max-w-2xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm space-y-8 relative overflow-hidden">
              <div class="absolute top-0 left-0 right-0 h-1 bg-[#8cc63f]"></div>
              
              <div class="flex items-center justify-between">
                <div class="flex-1 space-y-6 relative">
                  <!-- 连接线 -->
                  <div class="absolute left-4 top-8 bottom-8 w-0.5 bg-gray-200 dark:bg-[#363c4e] z-0"></div>
                  
                  <div class="relative z-10">
                    <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2 pl-12 relative">
                      <div class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 rounded-full border border-gray-200 dark:border-[#2b3139]-2 border border-gray-200 dark:border-[#2b3139]-gray-400 bg-white dark:bg-[#131722]"></div>{{ localeStore.t('fromAccount') }}</div>
                    <div class="relative pl-10">
                      <select v-model="transferForm.fromAccount" @change="handleTransferAccountChange" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3.5 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-800 dark:text-gray-100 font-medium appearance-none shadow-inner inset-shadow">
                        <option value="FUND">{{ localeStore.t('fundAccount') }}</option>
                        <option value="CONTRACT">{{ localeStore.t('contractAccount') }}</option>
                        <option value="OPTION">{{ localeStore.t('optionsAccount') }}</option>
                      </select>
                      <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowDown /></el-icon>
                    </div>
                  </div>
                  
                  <div class="relative z-10">
                    <div class="text-sm font-bold text-gray-700 dark:text-gray-200 mb-2 pl-12 relative">
                      <div class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 rounded-full bg-[#8cc63f] ring-4 ring-green-50"></div>{{ localeStore.t('toAccount') }}</div>
                    <div class="relative pl-10">
                      <select v-model="transferForm.toAccount" @change="handleTransferAccountChange" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3.5 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-800 dark:text-gray-100 font-medium appearance-none shadow-inner inset-shadow">
                        <option value="FUND">{{ localeStore.t('fundAccount') }}</option>
                        <option value="CONTRACT">{{ localeStore.t('contractAccount') }}</option>
                        <option value="OPTION">{{ localeStore.t('optionsAccount') }}</option>
                      </select>
                      <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowDown /></el-icon>
                    </div>
                  </div>
                </div>
                
                <div class="ml-6 flex items-center justify-center">
                  <button @click="swapTransferAccounts" class="w-12 h-12 rounded-full bg-green-50 text-[#8cc63f] hover:bg-[#8cc63f] hover:text-white transition-all shadow-sm flex items-center justify-center border border-gray-200 dark:border-[#2b3139] border border-gray-200 dark:border-[#2b3139]-green-100 group">
                    <svg class="w-6 h-6 transition-transform group-hover:rotate-180 duration-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"></path>
                    </svg>
                  </button>
                </div>
              </div>
              
              <div class="pt-6 border-t border-gray-100 dark:border-[#2b3139]">
                <div class="flex justify-between items-end mb-3">
                  <div class="text-sm font-bold text-gray-700 dark:text-gray-200">{{ localeStore.t('transferAmount') }}</div>
                  <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('availableText') }}: <span class="font-mono text-gray-800 dark:text-gray-100 font-bold ml-1">{{ getAvailableTransferBalance().toFixed(2) }} USD</span></div>
                </div>
                <div class="relative">
                  <input v-model="transferForm.amount" type="number" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg pl-4 pr-24 py-4 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-800 dark:text-gray-100 text-lg font-mono" placeholder="0.00" />
                  <div class="absolute right-2 top-1/2 -translate-y-1/2 flex items-center space-x-2">
                    <span class="text-gray-400 dark:text-gray-500 font-bold pr-2 border-r border-gray-200 dark:border-[#2b3139]">USD</span>
                    <button @click="transferForm.amount = getAvailableTransferBalance().toString()" class="text-[#8cc63f] font-bold px-2 py-1 hover:bg-green-50 rounded transition-colors text-sm">{{ localeStore.t('all') }}</button>
                  </div>
                </div>
              </div>
              
              <button @click="submitTransfer" class="w-full bg-[#8cc63f] text-white py-4 rounded-xl font-bold hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50 text-lg tracking-widest">{{ localeStore.t('confirmTransfer') }}</button>
            </div>
            
            <div class="mt-6 bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm">
              <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100 mb-4">{{ localeStore.t('transferRecords') }}</h3>
              <div v-if="transferRecords.length === 0" class="text-center py-6 text-gray-400 dark:text-gray-500">
                <el-empty :description="`${localeStore.t('noneText2')}${localeStore.t('transferText')}${localeStore.t('recordText2')}`" :image-size="60"></el-empty>
              </div>
              <div v-else class="space-y-4">
                <div v-for="record in transferRecords" :key="record.id" class="border border-gray-200 dark:border-[#2b3139]-b border border-gray-200 dark:border-[#2b3139]-gray-50 pb-4 last:border border-gray-200 dark:border-[#2b3139]-0 last:pb-0">
                  <div class="flex justify-between items-start mb-2">
                    <div class="font-bold text-gray-800 dark:text-gray-100 text-base">{{ localeStore.t('quantityText') }}: {{ Number(record.amount).toFixed(2) }}</div>
                  </div>
                  <div class="text-sm text-gray-600 dark:text-gray-300 mb-1">
                    {{ getAccountName(record.fromAccount) }} -- {{ getAccountName(record.toAccount) }}
                  </div>
                  <div class="text-xs text-gray-400 dark:text-gray-500">{{ record.createdAt }}</div>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'wallet'" class="max-w-2xl mx-auto pb-6">
            <div class="space-y-4">
              <!-- 总资产卡片 -->
              <div class="bg-white dark:bg-[#131722] p-6 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden">
                <div class="flex justify-between items-start mb-4 relative z-10">
                  <h3 class="text-xl font-bold text-gray-800 dark:text-gray-100">{{ localeStore.t('myAssets') }}</h3>
                  <span class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('totalAssetsEquivalent') }}</span>
                </div>
                <div class="flex justify-between items-center relative z-10">
                  <div class="text-4xl text-[#8cc63f] font-bold font-mono tracking-tight">
                    {{ showWalletAsset ? '$' + totalAsset.toFixed(2) : '****' }}
                  </div>
                  <el-icon @click="showWalletAsset = !showWalletAsset" class="text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 cursor-pointer text-xl transition-colors">
                    <component :is="showWalletAsset ? 'Hide' : 'View'" />
                  </el-icon>
                </div>
              </div>
              
              <!-- {{ localeStore.t('bankCardText2') }}区域 -->
              <div class="flex justify-between items-center mt-6 mb-2 px-1">
                <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100">{{ localeStore.t('bankCardLabel') }}</h3>
                <el-icon @click="openBindBankModal(null)" class="text-xl text-[#8cc63f] cursor-pointer hover:text-[#7ab036]"><Plus /></el-icon>
              </div>
              <div v-if="userBankCards.length === 0" @click="openBindBankModal(null)" class="bg-white dark:bg-[#131722] p-5 rounded-xl border border-gray-200 dark:border-[#2b3139] border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 shadow-sm flex justify-center items-center cursor-pointer hover:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] hover:text-[#8cc63f] transition-all group text-gray-500 dark:text-gray-400 dark:text-gray-500">
                <el-icon class="mr-2 text-xl"><Plus /></el-icon>
                <span class="font-medium">{{ localeStore.t('addBankCard') }}</span>
              </div>
              <div v-else class="space-y-3">
                <div v-for="card in userBankCards" :key="card.id" @click="openBindBankModal(card)" class="bg-white dark:bg-[#131722] p-5 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm cursor-pointer hover:border-[#8cc63f] hover:shadow-md transition-all relative overflow-hidden group">
                  <div class="absolute left-0 top-0 bottom-0 w-1 bg-[#8cc63f] opacity-0 group-hover:opacity-100 transition-opacity"></div>
                  <div class="flex justify-between items-start mb-4">
                    <div>
                      <div class="font-bold text-gray-800 dark:text-gray-100 text-lg">{{ card.currency || 'CNY' }}</div>
                      <div class="text-gray-500 dark:text-gray-400 dark:text-gray-500 text-sm mt-0.5">{{ card.bankName }}</div>
                    </div>
                    <el-icon class="text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f]"><Edit /></el-icon>
                  </div>
                  <div class="space-y-2 text-sm">
                    <div class="flex justify-between">
                      <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('payee') }}</span>
                      <span class="text-gray-800 dark:text-gray-100 font-medium">{{ card.recipientName }}</span>
                    </div>
                    <div class="flex justify-between">
                      <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('payeeAccount') }}</span>
                      <span class="text-gray-800 dark:text-gray-100 font-medium font-mono">{{ card.recipientAccount }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 数字货币地址区域 -->
              <div class="flex justify-between items-center mt-6 mb-2 px-1">
                <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100">{{ localeStore.t('digitalAddress') }}</h3>
                <el-icon @click="openBindDigitalModal(null)" class="text-xl text-[#8cc63f] cursor-pointer hover:text-[#7ab036]"><Plus /></el-icon>
              </div>
              <div v-if="userDigitalAddresses.length === 0" @click="openBindDigitalModal(null)" class="bg-white dark:bg-[#131722] p-5 rounded-xl border border-gray-200 dark:border-[#2b3139] border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 shadow-sm flex justify-center items-center cursor-pointer hover:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] hover:text-[#8cc63f] transition-all group text-gray-500 dark:text-gray-400 dark:text-gray-500">
                <el-icon class="mr-2 text-xl"><Plus /></el-icon>
                <span class="font-medium">{{ localeStore.t('addDigitalAddress') }}</span>
              </div>
              <div v-else class="space-y-3">
                <div v-for="addr in userDigitalAddresses" :key="addr.id" @click="openBindDigitalModal(addr)" class="bg-white dark:bg-[#131722] p-5 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm cursor-pointer hover:border-[#8cc63f] hover:shadow-md transition-all relative overflow-hidden group">
                  <div class="absolute left-0 top-0 bottom-0 w-1 bg-[#8cc63f] opacity-0 group-hover:opacity-100 transition-opacity"></div>
                  <div class="flex justify-between items-start mb-4">
                    <div>
                      <div class="font-bold text-gray-800 dark:text-gray-100 text-lg">{{ addr.currency }}</div>
                      <div class="text-gray-500 dark:text-gray-400 dark:text-gray-500 text-sm mt-0.5">{{ addr.network }}</div>
                    </div>
                    <el-icon class="text-gray-400 dark:text-gray-500 group-hover:text-[#8cc63f]"><Edit /></el-icon>
                  </div>
                  <div class="space-y-2 text-sm">
                    <div class="flex justify-between">
                      <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('walletAddressLabel') }}</span>
                      <span class="text-gray-800 dark:text-gray-100 font-medium font-mono truncate ml-4" :title="addr.address">{{ addr.address }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'kyc'" class="max-w-2xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden">
              <div class="absolute top-0 left-0 right-0 h-1" :class="isKycVerified ? 'bg-[#8cc63f]' : (kycStatus === 'PENDING' ? 'bg-orange-400' : 'bg-gray-300')"></div>
              
              <div class="mb-8 border border-gray-200 dark:border-[#2b3139]-b border border-gray-200 dark:border-[#2b3139]-gray-50 pb-6 flex items-center space-x-4">
                <el-icon v-if="isKycVerified" class="text-5xl text-[#8cc63f]"><SuccessFilled /></el-icon>
                <el-icon v-else-if="kycStatus === 'PENDING'" class="text-5xl text-orange-400"><WarningFilled /></el-icon>
                <el-icon v-else class="text-5xl text-gray-300"><Box /></el-icon>
                
                <div>
                  <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-1">{{ localeStore.t('kyc') }}</h2>
                  <p class="text-gray-500 dark:text-gray-400 dark:text-gray-500 text-sm">
                    <span v-if="isKycVerified" class="text-[#8cc63f] font-medium">{{ localeStore.t('kycPassedFeatureNormal') }}。</span>
                    <span v-else-if="kycStatus === 'PENDING'" class="text-orange-500 font-medium">{{ localeStore.t('yourText') }}{{ localeStore.t('authText') }}{{ localeStore.t('infoIsBeing') }}{{ localeStore.t('reviewingText') }}，{{ localeStore.t('pleaseWaitPatiently') }}。</span>
                    <span v-else>{{ localeStore.t('completeRealName') }}{{ localeStore.t('authText') }}{{ localeStore.t('toUnlockAdvancedFeatures') }}。</span>
                  </p>
                </div>
              </div>
              
              <div v-if="isKycVerified || kycStatus === 'PENDING'" class="space-y-4">
                <div class="bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg flex justify-between items-center">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('nameText') }}</span>
                  <span class="font-bold text-gray-800 dark:text-gray-100">{{ personalInfoForm.realName || '***' }}</span>
                </div>
                <div class="bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg flex justify-between items-center">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('idNumber') }}</span>
                  <span class="font-bold text-gray-800 dark:text-gray-100 font-mono">{{ personalInfoForm.idNumber ? personalInfoForm.idNumber.replace(/^(.{4})(.*)(.{4})$/, '$1******$3') : '***' }}</span>
                </div>
                <div class="bg-gray-50 dark:bg-[#181c27] p-4 rounded-lg flex justify-between items-center">
                  <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('authText') }}{{ localeStore.t('timeText') }}</span>
                  <span class="text-gray-800 dark:text-gray-100">{{ (personalInfoForm as any).updatedAt || (personalInfoForm as any).createdAt || '***' }}</span>
                </div>
              </div>
              
              <div v-else class="space-y-5">
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('realName') }}<span class="text-red-500">*</span></div>
                  <input v-model="personalInfoForm.realName" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterRealName')" />
                </div>
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('idNumber') }}<span class="text-red-500">*</span></div>
                  <input v-model="personalInfoForm.idNumber" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterIdNumber')" />
                </div>
                
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('uploadIdPhoto') }} <span class="text-red-500">*</span></div>
                  <div class="grid grid-cols-3 gap-4">
                    <!-- 身份证正面 -->
                    <div class="border-2 border-dashed border-gray-200 dark:border-[#2b3139] rounded-lg hover:border-[#8cc63f] transition-colors cursor-pointer overflow-hidden aspect-[4/3] bg-gray-50 dark:bg-[#181c27] relative">
                      <input 
                        ref="kycFrontInput"
                        type="file" 
                        accept="image/*" 
                        @change="(e) => handleImageUpload(e, 'kycFront')"
                        class="hidden"
                      />
                      <div 
                        v-if="personalInfoForm.idCardFront" 
                        class="w-full h-full p-1 relative group"
                        @click="triggerKycFrontUpload"
                      >
                        <img :src="getImageUrl(personalInfoForm.idCardFront)" class="w-full h-full object-contain rounded" />
                        <div class="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                          <el-icon class="text-white text-2xl"><Camera /></el-icon>
                        </div>
                      </div>
                      <div 
                        v-else 
                        class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] p-4 transition-colors"
                        @click="triggerKycFrontUpload"
                      >
                        <el-icon class="text-3xl mb-2"><Plus /></el-icon>
                        <div class="text-xs text-center leading-tight">{{ localeStore.t('clickToUpload') }}<br/>{{ localeStore.t('idCardFront') }}</div>
                      </div>
                    </div>
                    
                    <!-- 身份证反面 -->
                    <div class="border-2 border-dashed border-gray-200 dark:border-[#2b3139] rounded-lg hover:border-[#8cc63f] transition-colors cursor-pointer overflow-hidden aspect-[4/3] bg-gray-50 dark:bg-[#181c27] relative">
                      <input 
                        ref="kycBackInput"
                        type="file" 
                        accept="image/*" 
                        @change="(e) => handleImageUpload(e, 'kycBack')"
                        class="hidden"
                      />
                      <div 
                        v-if="personalInfoForm.idCardBack" 
                        class="w-full h-full p-1 relative group"
                        @click="triggerKycBackUpload"
                      >
                        <img :src="getImageUrl(personalInfoForm.idCardBack)" class="w-full h-full object-contain rounded" />
                        <div class="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                          <el-icon class="text-white text-2xl"><Camera /></el-icon>
                        </div>
                      </div>
                      <div 
                        v-else 
                        class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] p-4 transition-colors"
                        @click="triggerKycBackUpload"
                      >
                        <el-icon class="text-3xl mb-2"><Plus /></el-icon>
                        <div class="text-xs text-center leading-tight">{{ localeStore.t('clickToUpload') }}<br/>{{ localeStore.t('idCardBack') }}</div>
                      </div>
                    </div>

                    <!-- 手持身份证 -->
                    <div class="border-2 border-dashed border-gray-200 dark:border-[#2b3139] rounded-lg hover:border-[#8cc63f] transition-colors cursor-pointer overflow-hidden aspect-[4/3] bg-gray-50 dark:bg-[#181c27] relative">
                      <input 
                        ref="kycHandInput"
                        type="file" 
                        accept="image/*" 
                        @change="(e) => handleImageUpload(e, 'kycHand')"
                        class="hidden"
                      />
                      <div 
                        v-if="personalInfoForm.idCardHand" 
                        class="w-full h-full p-1 relative group"
                        @click="triggerKycHandUpload"
                      >
                        <img :src="getImageUrl(personalInfoForm.idCardHand)" class="w-full h-full object-contain rounded" />
                        <div class="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                          <el-icon class="text-white text-2xl"><Camera /></el-icon>
                        </div>
                      </div>
                      <div 
                        v-else 
                        class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] p-4 transition-colors"
                        @click="triggerKycHandUpload"
                      >
                        <el-icon class="text-3xl mb-2"><Plus /></el-icon>
                        <div class="text-xs text-center leading-tight">{{ localeStore.t('clickToUpload') }}<br/>{{ localeStore.t('remaining1') }}</div>
                      </div>
                    </div>
                  </div>
                  <div class="text-xs text-gray-400 dark:text-gray-500 mt-2">{{ localeStore.t('ensurePhotoClearSizeLimit') }}</div>
                </div>
                
                <div class="pt-4">
                  <button @click="submitPersonalInfoFromKyc" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50 tracking-wider">{{ localeStore.t('submitReview') }}</button>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'announcement'" class="max-w-2xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden">
              <div class="absolute top-0 left-0 w-full h-1 bg-[#8cc63f]"></div>
              <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-6 flex items-center"><el-icon class="mr-2 text-[#8cc63f]"><Bell /></el-icon>{{ localeStore.t('announcementNotification') }}</h2>
              
              <div v-if="announcements.length === 0" class="text-center py-10 text-gray-400 dark:text-gray-500">
                <el-empty :description="localeStore.t('noNotices')" :image-size="60"></el-empty>
              </div>
              <div v-else class="space-y-4">
                <div v-for="item in announcements" :key="item.id" class="border border-gray-100 dark:border-[#2b3139] rounded-xl p-5 hover:border-[#8cc63f] hover:shadow-md transition-all group bg-gray-50 dark:bg-[#181c27]/50">
                  <div class="flex justify-between items-start mb-3">
                    <h3 class="font-bold text-gray-800 dark:text-gray-100 text-lg group-hover:text-[#8cc63f] transition-colors">{{ item.title }}</h3>
                    <span class="text-xs text-gray-400 dark:text-gray-500 font-mono bg-white dark:bg-[#131722] px-2 py-1 rounded shadow-sm border border-gray-100 dark:border-[#2b3139]">{{ item.createdAt?.substring(0,10) }}</span>
                  </div>
                  <div class="text-gray-600 dark:text-gray-300 text-sm leading-relaxed whitespace-pre-line">{{ item.content }}</div>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'invite'" class="max-w-2xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden text-center space-y-6">
              <div class="absolute top-0 left-0 w-full h-1 bg-[#8cc63f]"></div>
              <el-icon class="text-6xl text-[#8cc63f] opacity-80"><Promotion /></el-icon>
              <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">{{ localeStore.t('inviteFriendsJoin') }}</h2>
              <p class="text-gray-500 dark:text-gray-400 dark:text-gray-500">{{ localeStore.t('inviteFriendsRegister') }}，{{ localeStore.t('getMoreRewards') }}</p>
              
              <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-6 border border-gray-100 dark:border-[#2b3139] inline-block text-left w-full max-w-sm">
                <div class="mb-4">
                  <vue-qrcode :value="inviteLink" :options="{ width: 160, color: { dark: '#1f2937' } }" class="mx-auto p-2 bg-white dark:bg-[#131722] rounded-lg shadow-sm border border-gray-100 dark:border-[#2b3139]" />
                </div>
                <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('yourExclusiveInviteCode') }}</div>
                <div class="font-mono font-medium text-gray-800 dark:text-gray-100 bg-white dark:bg-[#131722] px-4 py-3 rounded border border-gray-200 dark:border-[#2b3139] flex justify-between items-center select-all">
                  <span>{{ auth.user?.id || '8959285729' }}</span>
                  <el-icon class="text-[#8cc63f] cursor-pointer hover:text-[#7ab036] text-lg" @click="copyToClipboard(auth.user?.id?.toString() || '8959285729')"><DocumentCopy /></el-icon>
                </div>
              </div>
              
              <div>
                <div class="text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mb-1">{{ localeStore.t('inviteLink') }}</div>
                <div class="font-mono text-xs text-gray-600 dark:text-gray-300 bg-gray-50 dark:bg-[#181c27] px-4 py-3 rounded border border-gray-200 dark:border-[#2b3139] flex justify-between items-center break-all text-left">
                  <span class="mr-2">{{ inviteLink }}</span>
                  <el-icon class="text-[#8cc63f] cursor-pointer hover:text-[#7ab036] text-lg shrink-0" @click="copyToClipboard(inviteLink)"><DocumentCopy /></el-icon>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'password'" class="max-w-xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden">
              <div class="absolute top-0 left-0 w-full h-1 bg-[#8cc63f]"></div>
              <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100 mb-6 flex items-center"><el-icon class="mr-2 text-[#8cc63f]"><Lock /></el-icon>{{ localeStore.t('changePassword') }}</h2>
              
              <div class="space-y-5">
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('oldPassword') }}<span class="text-red-500">*</span></div>
                  <input v-model="passwordForm.oldPassword" type="password" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterOldPassword')" />
                </div>
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('newPassword') }}<span class="text-red-500">*</span></div>
                  <input v-model="passwordForm.newPassword" type="password" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterNewPassword')" />
                </div>
                <div>
                  <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('confirmText') }}{{ localeStore.t('newPassword2') }} <span class="text-red-500">*</span></div>
                  <input v-model="passwordForm.confirmPassword" type="password" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterConfirmPassword')" />
                </div>
                <div class="pt-4">
                  <button @click="submitPasswordChange" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('confirmText') }}修改</button>
                </div>
              </div>
            </div>
          </div>
          
          <div v-else-if="activeUserMenu === 'support'" class="max-w-xl mx-auto pb-6">
            <div class="bg-white dark:bg-[#131722] p-8 rounded-xl border border-gray-100 dark:border-[#2b3139] shadow-sm relative overflow-hidden text-center space-y-6">
              <div class="absolute top-0 left-0 w-full h-1 bg-[#8cc63f]"></div>
              <div class="w-20 h-20 bg-green-50 rounded-full flex items-center justify-center mx-auto text-[#8cc63f] text-4xl shadow-sm">
                <el-icon><Service /></el-icon>
              </div>
              <h2 class="text-2xl font-bold text-gray-800 dark:text-gray-100">{{ localeStore.t('support') }}</h2>
              <p class="text-gray-500 dark:text-gray-400 dark:text-gray-500">24{{ localeStore.t('hoursOnlineService2') }}</p>
              
              <div class="bg-gray-50 dark:bg-[#181c27] rounded-xl p-6 border border-gray-100 dark:border-[#2b3139] text-left space-y-4">
                <div v-if="customerServiceLink" class="flex items-center justify-between p-3 bg-white dark:bg-[#131722] rounded border border-gray-100 dark:border-[#2b3139] shadow-sm">
                  <div class="flex items-center text-gray-700 dark:text-gray-200 font-medium">
                    <el-icon class="w-6 h-6 mr-3 text-2xl text-[#8cc63f]"><Service /></el-icon>
                    {{ localeStore.t('onlineCustomerService') }}
                  </div>
                  <a :href="customerServiceLink" target="_blank" class="text-[#8cc63f] hover:underline text-sm font-bold">{{ localeStore.t('clickToContact') }}</a>
                </div>
                
                <div v-if="complaintEmail" class="flex items-center justify-between p-3 bg-white dark:bg-[#131722] rounded border border-gray-100 dark:border-[#2b3139] shadow-sm">
                  <div class="flex items-center text-gray-700 dark:text-gray-200 font-medium">
                    <el-icon class="w-6 h-6 mr-3 text-2xl text-blue-500"><Message /></el-icon>
                    {{ localeStore.t('officialEmail') }}
                  </div>
                  <a :href="'mailto:' + complaintEmail" class="text-[#8cc63f] hover:underline text-sm font-bold">{{ complaintEmail }}</a>
                </div>

                <div v-if="!customerServiceLink && !complaintEmail" class="text-center text-gray-500 py-4">
                  {{ localeStore.t('noCustomerService') }}
                </div>
              </div>
            </div>
          </div>
          
          <div v-else class="flex h-full flex-col items-center justify-center text-gray-400 dark:text-gray-500">
            <el-icon class="text-6xl mb-4 opacity-50"><Box /></el-icon>
            <div class="text-lg font-medium">{{ localeStore.t('contentUnderConstruction') }}...</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="showTpSlModal" :title="localeStore.t('setTpSl')" width="400px" class="custom-dialog rounded-xl overflow-hidden">
      <div v-if="activeOrder" class="space-y-4 px-2 py-4">
        <div class="flex justify-between items-center mb-2">
          <span class="text-gray-600 dark:text-gray-300 font-bold">{{ activeOrder.symbol }}</span>
          <span :class="activeOrder.type === 'buy' ? 'text-[#8cc63f]' : 'text-[#ff4d4f]'">{{ activeOrder.type === 'buy' ? localeStore.t('buyIn') : localeStore.t('sellText') }} {{ activeOrder.lots }}{{ localeStore.t('lot') }}</span>
        </div>
        
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('stopLossPrice') }}</div>
          <el-input-number v-model="tpSlForm.stopLoss" class="w-full custom-input-number" :controls="true" :min="0" :step="0.01" />
        </div>
        
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('takeProfitPrice') }}</div>
          <el-input-number v-model="tpSlForm.takeProfit" class="w-full custom-input-number" :controls="true" :min="0" :step="0.01" />
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2 flex space-x-3">
          <button @click="showTpSlModal = false" class="flex-1 bg-gray-100 dark:bg-[#2b3139] text-gray-600 dark:text-gray-300 py-3 rounded-lg font-bold text-sm hover:bg-gray-200 dark:bg-[#363c4e] transition-colors">{{ localeStore.t('cancel') }}</button>
          <button @click="submitTpSl" class="flex-1 bg-[#8cc63f] text-white py-3 rounded-lg font-bold text-sm hover:bg-[#7ab036] transition-colors shadow-sm shadow-green-200">{{ localeStore.t('confirm') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showPersonalInfoModal" :title="localeStore.t('personalInfoVerification')" width="450px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-2 max-h-[60vh] overflow-y-auto custom-scrollbar">
        <div class="bg-blue-50 text-blue-600 p-3 rounded-lg text-sm mb-4">
          {{ localeStore.t('forPurpose') }}{{ localeStore.t('yourText') }}{{ localeStore.t('fundSecurity') }}，{{ localeStore.t('pleaseText') }}{{ localeStore.t('completeRealInfoFirst') }}{{ localeStore.t('authText') }}{{ localeStore.t('submitWaitAdminReview') }}。
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('realName') }}<span class="text-red-500">*</span></div>
          <input v-model="personalInfoForm.realName" type="text" class="w-full border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-2.5 outline-none focus:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus:ring-1 focus:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]" :placeholder="localeStore.t('enterRealName')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('idNumber') }}<span class="text-red-500">*</span></div>
          <input v-model="personalInfoForm.idNumber" type="text" class="w-full border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-2.5 outline-none focus:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus:ring-1 focus:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]" :placeholder="localeStore.t('enterIdNumber')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('contactPhone') }}<span class="text-red-500">*</span></div>
          <input v-model="personalInfoForm.phone" type="text" class="w-full border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-2.5 outline-none focus:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus:ring-1 focus:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722]" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('yourContactPhone')}`" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('homeAddress2') }} <span class="text-red-500">*</span></div>
          <textarea v-model="personalInfoForm.address" class="w-full border border-gray-200 dark:border-[#2b3139] rounded-lg px-4 py-2.5 outline-none focus:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] focus:ring-1 focus:ring-[#8cc63f]/20 transition-all bg-white dark:bg-[#131722] resize-none h-20" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('yourText')}${localeStore.t('detailText')}${localeStore.t('homeAddress2')}`"></textarea>
        </div>
        
        <div class="space-y-4 pt-2 border-t border-gray-200 dark:border-[#2b3139] mt-4">
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm">{{ localeStore.t('uploadIdPhoto') }} <span class="text-red-500">*</span></div>
          <div class="grid grid-cols-3 gap-4">
            <div class="flex flex-col items-center">
              <div class="w-full h-24 border border-gray-200 dark:border-[#2b3139]-2 border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 rounded-lg hover:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] transition-colors cursor-pointer overflow-hidden bg-gray-50 dark:bg-[#181c27] relative">
                <input 
                  ref="loanKycFrontInput"
                  type="file" 
                  accept="image/*" 
                  @change="(e) => handleImageUpload(e, 'kycFront')"
                  class="hidden"
                />
                <div 
                  v-if="personalInfoForm.idCardFront" 
                  class="w-full h-full relative group"
                  @click="triggerLoanKycFrontUpload"
                >
                  <img :src="getImageUrl(personalInfoForm.idCardFront)" class="w-full h-full object-cover" />
                  <div class="absolute inset-0 bg-black/50 hidden group-hover:flex items-center justify-center text-white text-xs">{{ localeStore.t('clickToChange') }}</div>
                </div>
                <div 
                  v-else 
                  class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] transition-colors"
                  @click="triggerLoanKycFrontUpload"
                >
                  <el-icon class="text-xl mb-1"><Plus /></el-icon>
                  <span class="text-xs">{{ localeStore.t('idCardFront') }}</span>
                </div>
              </div>
            </div>
            <div class="flex flex-col items-center">
              <div class="w-full h-24 border border-gray-200 dark:border-[#2b3139]-2 border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 rounded-lg hover:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] transition-colors cursor-pointer overflow-hidden bg-gray-50 dark:bg-[#181c27] relative">
                <input 
                  ref="loanKycBackInput"
                  type="file" 
                  accept="image/*" 
                  @change="(e) => handleImageUpload(e, 'kycBack')"
                  class="hidden"
                />
                <div 
                  v-if="personalInfoForm.idCardBack" 
                  class="w-full h-full relative group"
                  @click="triggerLoanKycBackUpload"
                >
                  <img :src="getImageUrl(personalInfoForm.idCardBack)" class="w-full h-full object-cover" />
                  <div class="absolute inset-0 bg-black/50 hidden group-hover:flex items-center justify-center text-white text-xs">{{ localeStore.t('clickToChange') }}</div>
                </div>
                <div 
                  v-else 
                  class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] transition-colors"
                  @click="triggerLoanKycBackUpload"
                >
                  <el-icon class="text-xl mb-1"><Plus /></el-icon>
                  <span class="text-xs">{{ localeStore.t('idCardBack') }}</span>
                </div>
              </div>
            </div>
            <div class="flex flex-col items-center">
              <div class="w-full h-24 border border-gray-200 dark:border-[#2b3139]-2 border border-gray-200 dark:border-[#2b3139]-dashed border border-gray-200 dark:border-[#2b3139]-gray-300 rounded-lg hover:border border-gray-200 dark:border-[#2b3139]-[#8cc63f] transition-colors cursor-pointer overflow-hidden bg-gray-50 dark:bg-[#181c27] relative">
                <input 
                  ref="loanKycHandInput"
                  type="file" 
                  accept="image/*" 
                  @change="(e) => handleImageUpload(e, 'kycHand')"
                  class="hidden"
                />
                <div 
                  v-if="personalInfoForm.idCardHand" 
                  class="w-full h-full relative group"
                  @click="triggerLoanKycHandUpload"
                >
                  <img :src="getImageUrl(personalInfoForm.idCardHand)" class="w-full h-full object-cover" />
                  <div class="absolute inset-0 bg-black/50 hidden group-hover:flex items-center justify-center text-white text-xs">{{ localeStore.t('clickToChange') }}</div>
                </div>
                <div 
                  v-else 
                  class="w-full h-full flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] transition-colors"
                  @click="triggerLoanKycHandUpload"
                >
                  <el-icon class="text-xl mb-1"><Camera /></el-icon>
                  <span class="text-xs">{{ localeStore.t('idCardHand') }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2 mt-2">
          <button @click="submitPersonalInfo" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('submitReview') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showBindBankModal" :title="localeStore.t('bindBankCardTitle')" width="450px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-4">
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('openingBankText') }} <span class="text-red-500">*</span></div>
          <input v-model="bindBankForm.bankName" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('openingBankText')}${localeStore.t('nameText2')}`" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('bankCardNoText') }} <span class="text-red-500">*</span></div>
          <input v-model="bindBankForm.recipientAccount" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('bankCardNoText')}`" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('accountNameText') }} <span class="text-red-500">*</span></div>
          <input v-model="bindBankForm.recipientName" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('accountNameText')}`" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('branchAddress') }}<span class="text-gray-400 dark:text-gray-500 font-normal text-xs">({{ localeStore.t('optionalText') }})</span></div>
          <input v-model="bindBankForm.bankAddress" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterBranchAddress')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('swiftCode') }} <span class="text-gray-400 dark:text-gray-500 font-normal text-xs">({{ localeStore.t('intlTransferRequired') }})</span></div>
          <input v-model="bindBankForm.swift" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('enterSwiftCode')" 
/>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="submitBindBank" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('save') }}</button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="showBindDigitalModal" :title="localeStore.t('bindDigitalAddressTitle')" width="450px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-4">
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('currencyType') }}<span class="text-red-500">*</span></div>
          <div class="relative">
            <select v-model="bindDigitalForm.currency" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
              <option value="" disabled selected hidden>{{ localeStore.t('pleaseText') }}{{ localeStore.t('select') }}{{ localeStore.t('currencyType') }}</option>
              <option v-for="c in ['USDT', 'USDC', 'BTC', 'ETH']" :key="c" :value="c">{{ c }}</option>
            </select>
            <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowDown /></el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('networkProtocol') }}<span class="text-red-500">*</span></div>
          <div class="relative">
            <select v-model="bindDigitalForm.network" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 appearance-none">
              <option value="" disabled selected hidden>{{ localeStore.t('pleaseText') }}{{ localeStore.t('selectNetworkText') }}</option>
              <option v-for="n in ['TRC20', 'ERC20', 'OMNI', 'BEP20']" :key="n" :value="n">{{ n }}</option>
            </select>
            <el-icon class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 pointer-events-none"><ArrowDown /></el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('walletAddressLabel') }}<span class="text-red-500">*</span></div>
          <textarea v-model="bindDigitalForm.address" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200 resize-none h-20" :placeholder="`${localeStore.t('pleaseEnterText')}${localeStore.t('orText')}${localeStore.t('pasteText')}${localeStore.t('walletAddressLabel')}`"></textarea>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="submitBindDigital" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50">{{ localeStore.t('save') }}</button>
        </div>
      </template>
    </el-dialog>

    <!-- 登录弹窗 -->
    <el-dialog v-model="showLoginModal" :title="localeStore.t('emailLogin')" width="400px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-4">
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('emailLogin') }} <span class="text-red-500">*</span></div>
          <input v-model="loginEmail" type="email" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('emailPlaceholder')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('password') }} <span class="text-red-500">*</span></div>
          <div class="relative">
            <input v-model="loginPassword" :type="loginShowPwd ? 'text' : 'password'" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('passwordPlaceholder')" />
            <el-icon @click="loginShowPwd = !loginShowPwd" class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 cursor-pointer hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 text-lg">
              <component :is="loginShowPwd ? 'Hide' : 'View'" />
            </el-icon>
          </div>
        </div>
        <div class="flex justify-between items-center text-sm mt-1">
          <span class="text-gray-500 dark:text-gray-400 dark:text-gray-500 whitespace-nowrap">
            {{ localeStore.t('newUserJoin') }}
            <a @click="showLoginModal = false; showRegisterModal = true" class="text-[#8cc63f] cursor-pointer font-bold hover:underline ml-1">{{ localeStore.t('register') }}</a>
          </span>
          <a @click="showLoginModal = false; showForgotModal = true" class="text-gray-400 dark:text-gray-500 hover:text-[#8cc63f] cursor-pointer transition-colors whitespace-nowrap">{{ localeStore.t('forgotPassword') }}</a>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="onLoginSubmit" :disabled="loginLoading" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50 disabled:opacity-50">
            {{ loginLoading ? localeStore.t('pleaseWait') : localeStore.t('login') }}
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- 注册弹窗 -->
    <el-dialog v-model="showRegisterModal" :title="localeStore.t('emailRegister')" width="450px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-4">
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('emailRegister') }} <span class="text-red-500">*</span></div>
          <input v-model="registerEmail" type="email" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('emailPlaceholder')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('password') }} <span class="text-red-500">*</span></div>
          <div class="relative">
            <input v-model="registerPassword" :type="registerShowPwd ? 'text' : 'password'" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('passwordPlaceholder')" />
            <el-icon @click="registerShowPwd = !registerShowPwd" class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 cursor-pointer hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 text-lg">
              <component :is="registerShowPwd ? 'Hide' : 'View'" />
            </el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('confirmPassword') }} <span class="text-red-500">*</span></div>
          <div class="relative">
            <input v-model="registerConfirmPassword" :type="registerShowPwd2 ? 'text' : 'password'" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('confirmPasswordPlaceholder')" />
            <el-icon @click="registerShowPwd2 = !registerShowPwd2" class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 cursor-pointer hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 text-lg">
              <component :is="registerShowPwd2 ? 'Hide' : 'View'" />
            </el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('inviteCode') }} <span class="text-red-500">*</span></div>
          <input v-model="registerInviteCode" :readonly="registerInviteLocked" type="text" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="registerInviteLocked ? localeStore.t('inviteCodeFilled') : localeStore.t('inviteCodeRequired')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('verifyCode') }} <span class="text-red-500">*</span></div>
          <div class="flex space-x-2">
            <input v-model="registerVerifyCode" type="text" class="flex-1 bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('verifyCodePlaceholder')" />
            <button @click="sendRegisterCode" :disabled="registerSending || registerCountdown > 0" class="bg-[#8cc63f] text-white px-4 py-3 rounded-lg font-bold text-sm hover:bg-[#7ab036] transition-colors disabled:opacity-50 whitespace-nowrap">
              {{ registerSending ? localeStore.t('sending') : registerCountdown > 0 ? `${registerCountdown}${localeStore.t('seconds')}` : localeStore.t('send') }}
            </button>
          </div>
        </div>
        <div class="text-center text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mt-4">
          {{ localeStore.t('gotoLogin') }}
          <a @click="showRegisterModal = false; showLoginModal = true" class="text-[#8cc63f] cursor-pointer font-bold hover:underline">{{ localeStore.t('login') }}</a>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="onRegisterSubmit" :disabled="registerLoading" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50 disabled:opacity-50">
            {{ registerLoading ? localeStore.t('submitting') : localeStore.t('register') }}
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- 忘记密码弹窗 -->
    <el-dialog v-model="showForgotModal" :title="localeStore.t('forgotPassword')" width="450px" class="custom-dialog rounded-xl overflow-hidden">
      <div class="space-y-4 px-2 py-4">
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('emailLogin') }} <span class="text-red-500">*</span></div>
          <input v-model="forgotEmail" type="email" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('emailPlaceholder')" />
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('password') }} <span class="text-red-500">*</span></div>
          <div class="relative">
            <input v-model="forgotPassword" :type="forgotShowPwd ? 'text' : 'password'" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('passwordPlaceholder')" />
            <el-icon @click="forgotShowPwd = !forgotShowPwd" class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 cursor-pointer hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 text-lg">
              <component :is="forgotShowPwd ? 'Hide' : 'View'" />
            </el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('confirmPassword') }} <span class="text-red-500">*</span></div>
          <div class="relative">
            <input v-model="forgotConfirmPassword" :type="forgotShowPwd2 ? 'text' : 'password'" class="w-full bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('confirmPasswordPlaceholder')" />
            <el-icon @click="forgotShowPwd2 = !forgotShowPwd2" class="absolute right-4 top-1/2 -translate-y-1/2 text-gray-400 dark:text-gray-500 cursor-pointer hover:text-gray-600 dark:hover:text-gray-300 dark:text-gray-300 text-lg">
              <component :is="forgotShowPwd2 ? 'Hide' : 'View'" />
            </el-icon>
          </div>
        </div>
        <div>
          <div class="text-gray-600 dark:text-gray-300 font-medium text-sm mb-2">{{ localeStore.t('verifyCode') }} <span class="text-red-500">*</span></div>
          <div class="flex space-x-2">
            <input v-model="forgotVerifyCode" type="text" class="flex-1 bg-gray-50 dark:bg-[#181c27] border border-gray-200 dark:border-[#2b3139]-none rounded-lg px-4 py-3 outline-none focus:ring-1 focus:ring-[#8cc63f]/30 transition-all text-gray-700 dark:text-gray-200" :placeholder="localeStore.t('verifyCodePlaceholder')" />
            <button @click="sendForgotCode" :disabled="forgotSending || forgotCountdown > 0" class="bg-[#8cc63f] text-white px-4 py-3 rounded-lg font-bold text-sm hover:bg-[#7ab036] transition-colors disabled:opacity-50 whitespace-nowrap">
              {{ forgotSending ? localeStore.t('sending') : forgotCountdown > 0 ? `${forgotCountdown}${localeStore.t('seconds')}` : localeStore.t('send') }}
            </button>
          </div>
        </div>
        <div class="text-center text-sm text-gray-500 dark:text-gray-400 dark:text-gray-500 mt-4">
          <a @click="showForgotModal = false; showLoginModal = true" class="text-[#8cc63f] cursor-pointer font-bold hover:underline">{{ localeStore.t('returnToLogin') }}</a>
        </div>
      </div>
      <template #footer>
        <div class="px-2 pb-2">
          <button @click="onForgotSubmit" :disabled="forgotLoading" class="w-full bg-[#8cc63f] text-white py-3.5 rounded-lg font-bold text-base hover:bg-[#7ab036] transition-colors shadow-md shadow-green-200/50 disabled:opacity-50">
            {{ forgotLoading ? localeStore.t('submitting') : localeStore.t('resetPassword') }}
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, watch, onUnmounted, nextTick } from 'vue';
import { useMarketStore } from '@/store/market';
import { useAuthStore } from '@/store/auth';
import { useLocaleStore } from '@/store/locale';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import request from '@/utils/request';
import { formatDateTime } from '@/utils/dateTime';
import { getImageUrl } from '@/utils/imageUrl';
import KlineChart from '@/components/KlineChart.vue';
import { Money, Coin, ArrowDown, Check, ArrowRight, Search, Box, Plus, Camera, UploadFilled, SuccessFilled, WarningFilled, Promotion, Lock, Service, DocumentCopy, Message, Bell, Edit, Moon, Sunny } from '@element-plus/icons-vue';
import VueQrcode from '@chenfengyuan/vue-qrcode';


const marketStore = useMarketStore();
const auth = useAuthStore();
const localeStore = useLocaleStore();
localeStore.loadLocale();
const router = useRouter();

// ======================
// 登录注册逻辑
// ======================
const showLoginModal = ref(false);

const loginEmail = ref('');
const loginPassword = ref('');
const loginShowPwd = ref(false);
const loginLoading = ref(false);

const onLoginSubmit = async () => {
  if (loginLoading.value) return;
  if (!loginEmail.value || !loginPassword.value) {
    ElMessage.error(localeStore.t('pleaseEnterEmailAndPassword'));
    return;
  }
  loginLoading.value = true;
  try {
    const res: any = await request.post('/auth/login', {
      account: loginEmail.value,
      password: loginPassword.value,
      loginType: 'email',
    });
    auth.setAuth(res.token, res.user);
    ElMessage.success(localeStore.t('loginSuccess'));
    showLoginModal.value = false;
    // 加载登录后{{ localeStore.t('requiredText2') }}的数据
    loadWalletBalances();
    loadFinancialOrders();
    loadKycStatus();
  } catch (e: any) {
    ElMessage.error(e?.message || localeStore.t('loginFail'));
  } finally {
    loginLoading.value = false;
  }
};

const showRegisterModal = ref(false);
const registerEmail = ref('');
const registerPassword = ref('');
const registerConfirmPassword = ref('');
const registerInviteCode = ref('');
const registerInviteLocked = ref(false);
const registerVerifyCode = ref('');
const registerSending = ref(false);
const registerLoading = ref(false);
const registerShowPwd = ref(false);
const registerShowPwd2 = ref(false);
const registerCountdown = ref(0);
const registerCountdownTimer = ref<number | null>(null);

const sendRegisterCode = async () => {
  if (registerSending.value || registerCountdown.value > 0) return;
  if (!registerEmail.value) {
    ElMessage.error(localeStore.t('sendCodeFail'));
    return;
  }
  registerSending.value = true;
  try {
    await request.post('/auth/sendEmailCode', {
      email: registerEmail.value,
      scene: 'register',
    });
    ElMessage.success(localeStore.t('sendCodeSuccess'));
    registerCountdown.value = 60;
    registerCountdownTimer.value = window.setInterval(() => {
      registerCountdown.value--;
      if (registerCountdown.value <= 0 && registerCountdownTimer.value !== null) {
        clearInterval(registerCountdownTimer.value);
        registerCountdownTimer.value = null;
      }
    }, 1000);
  } catch (e: any) {
    ElMessage.error(e?.message || localeStore.t('sendCodeFail'));
  } finally {
    registerSending.value = false;
  }
};

const onRegisterSubmit = async () => {
  if (registerLoading.value) return;
  if (!registerEmail.value || !registerPassword.value || !registerConfirmPassword.value || !registerVerifyCode.value || !registerInviteCode.value) {
    ElMessage.error(localeStore.t('pleaseEnterAllRequiredFields'));
    return;
  }
  if (registerPassword.value !== registerConfirmPassword.value) {
    ElMessage.error(localeStore.t('passwordsNotMatch'));
    return;
  }
  if (registerPassword.value.length < 6) {
    ElMessage.error(localeStore.t('passwordTooShort'));
    return;
  }
  registerLoading.value = true;
  try {
    await request.post('/auth/register', {
      email: registerEmail.value,
      password: registerPassword.value,
      confirmPassword: registerConfirmPassword.value,
      verifyCode: registerVerifyCode.value,
      invitationCode: registerInviteCode.value,
    });
    ElMessage.success(localeStore.t('registerSuccess'));
    showRegisterModal.value = false;
    showLoginModal.value = true;
    loginEmail.value = registerEmail.value;
  } catch (e: any) {
    ElMessage.error(e?.message || localeStore.t('registerFail'));
  } finally {
    registerLoading.value = false;
  }
};


const showForgotModal = ref(false);
const forgotEmail = ref('');
const forgotPassword = ref('');
const forgotConfirmPassword = ref('');
const forgotVerifyCode = ref('');
const forgotSending = ref(false);
const forgotLoading = ref(false);
const forgotShowPwd = ref(false);
const forgotShowPwd2 = ref(false);
const forgotCountdown = ref(0);
const forgotCountdownTimer = ref<number | null>(null);

const sendForgotCode = async () => {
  if (forgotSending.value || forgotCountdown.value > 0) return;
  if (!forgotEmail.value) {
    ElMessage.error(localeStore.t('pleaseEnterEmail'));
    return;
  }
  forgotSending.value = true;
  try {
    await request.post('/auth/sendEmailCode', {
      email: forgotEmail.value,
      scene: 'forget_password',
    });
    ElMessage.success(localeStore.t('codeSentCheckEmail'));
    forgotCountdown.value = 60;
    forgotCountdownTimer.value = window.setInterval(() => {
      forgotCountdown.value--;
      if (forgotCountdown.value <= 0 && forgotCountdownTimer.value !== null) {
        clearInterval(forgotCountdownTimer.value);
        forgotCountdownTimer.value = null;
      }
    }, 1000);
  } catch (e: any) {
    ElMessage.error(e?.message || localeStore.t('sendFailed'));
  } finally {
    forgotSending.value = false;
  }
};

const onForgotSubmit = async () => {
  if (forgotLoading.value) return;
  if (!forgotEmail.value || !forgotPassword.value || !forgotConfirmPassword.value || !forgotVerifyCode.value) {
    ElMessage.error(localeStore.t('pleaseFillAllFields'));
    return;
  }
  if (forgotPassword.value !== forgotConfirmPassword.value) {
    ElMessage.error(localeStore.t('passwordsNotMatch'));
    return;
  }
  forgotLoading.value = true;
  try {
    await request.post('/auth/resetPassword', {
      email: forgotEmail.value,
      password: forgotPassword.value,
      confirmPassword: forgotConfirmPassword.value,
      verifyCode: forgotVerifyCode.value,
    });
    ElMessage.success(localeStore.t('resetSuccessLogin'));
    showForgotModal.value = false;
    showLoginModal.value = true;
    loginEmail.value = forgotEmail.value;
  } catch (e: any) {
    ElMessage.error(e?.message || localeStore.t('resetFailed'));
  } finally {
    forgotLoading.value = false;
  }
};

// 监听路由参数，如果有邀请码或特定参数则打开对应弹窗
const checkRouteQuery = () => {
  const query = router.currentRoute.value.query;
  const qInvite = query.invite || query.invitationCode;
  const isLogin = query.login;
  const isRegister = query.register;
  const isForgot = query.forgot;
  
  let shouldClearQuery = false;
  
  if (qInvite || isRegister) {
    if (qInvite) {
      registerInviteCode.value = qInvite as string;
      registerInviteLocked.value = true;
    }
    showRegisterModal.value = true;
    shouldClearQuery = true;
  } else if (isLogin) {
    showLoginModal.value = true;
    shouldClearQuery = true;
  } else if (isForgot) {
    showForgotModal.value = true;
    shouldClearQuery = true;
  }
  
  if (shouldClearQuery) {
    // 移除查询参数，保持 URL 干净
    router.replace({ path: router.currentRoute.value.path });
  }
};

onMounted(() => {
  checkRouteQuery();
});

watch(() => router.currentRoute.value.query, () => {
  checkRouteQuery();
});

onUnmounted(() => {
  if (timeInterval) {
    clearInterval(timeInterval);
  }
  if (registerCountdownTimer.value !== null) {
    clearInterval(registerCountdownTimer.value);
    registerCountdownTimer.value = null;
  }
  if (forgotCountdownTimer.value !== null) {
    clearInterval(forgotCountdownTimer.value);
    forgotCountdownTimer.value = null;
  }
});

const languages = [
  { label: 'English', locale: 'en' },
  { label: 'français', locale: 'fr' },
  { label: 'Deutsche', locale: 'de' },
  { label: 'Русский язык', locale: 'ru' },
  { label: 'Español', locale: 'es' },
  { label: 'Português', locale: 'pt' },
  { label: 'Italiano', locale: 'it' },
  { label: 'عربي', locale: 'ar' },
  { label: 'Türkçe', locale: 'tr' },
  { label: 'Indonesia', locale: 'id' },
  { label: 'မြန်မာ', locale: 'my' },
  { label: 'हिंदी', locale: 'hi' },
  { label: 'čeština', locale: 'cs' },
  { label: 'Polska', locale: 'pl' },
  { label: '日本語', locale: 'ja' },
  { label: '한국어', locale: 'ko' },
  { label: 'ไทย', locale: 'th' },
  { label: 'Tiếng Việt', locale: 'vi' },
  { label: '繁体中文', locale: 'zh-TW' },
];

const currentLangLabel = computed(() => {
  const lang = languages.find(l => l.locale === localeStore.getCurrentLocale());
  return lang ? lang.label : 'English';
});

const handleLangChange = (locale: string) => {
  localeStore.setLocale(locale as any);
};


const parseErrorMsg = (e: any, defaultMsg: string) => {
  let errMsg = '';
  if (e?.response?.data?.message) {
    errMsg = e.response.data.message;
  } else if (e?.message && !e.message.includes('Network Error') && !e.message.includes('status code')) {
    errMsg = e.message;
  } else if (typeof e === 'string') {
    errMsg = e;
  } else {
    errMsg = defaultMsg;
  }

  if (errMsg.includes('合约资产余额不足') || errMsg.includes(localeStore.t('contractBalanceInsufficient2'))) {
    const match = errMsg.match(/需要[：:]\s*([\d.]+)[，,]\s*可用[：:]\s*([\d.]+)/);
    if (match) {
      return `${localeStore.t('contractBalanceInsufficient')}, ${localeStore.t('requiredText')}: ${match[1]}, ${localeStore.t('availableText')}: ${match[2]}`;
    }
    return localeStore.t('contractBalanceInsufficient');
  }
  if (errMsg.includes('期权资产余额不足') || errMsg.includes(localeStore.t('optionBalanceInsufficient2'))) {
    return localeStore.t('optionBalanceInsufficient');
  }
  if (errMsg === 'Network Error') {
    return localeStore.t('networkError');
  }
  return errMsg;
};

// ======================
// 黑夜模式逻辑
// ======================
const isDarkMode = ref(false);

const toggleDarkMode = (val: boolean) => {
  isDarkMode.value = val;
  if (val) {
    document.documentElement.classList.add('dark');
    localStorage.setItem('theme', 'dark');
  } else {
    document.documentElement.classList.remove('dark');
    localStorage.setItem('theme', 'light');
  }
};

onMounted(() => {
  // 初始化黑夜模式
  const savedTheme = localStorage.getItem('theme');
  if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
    isDarkMode.value = true;
    document.documentElement.classList.add('dark');
  } else {
    isDarkMode.value = false;
    document.documentElement.classList.remove('dark');
  }
});

const currentSymbol = ref('XAUUSD');
const currentCategory = ref('');
const currentInterval = ref('5m');

const searchQuery = ref('');
const tradeMode = ref('contract');
const orderType = ref('market');
const limitPrice = ref<number>();

watch(orderType, (newVal) => {
  if (newVal === 'limit') {
    const pInfo = marketStore.priceMap[currentSymbol.value];
    limitPrice.value = pInfo && pInfo.price ? Number(pInfo.price) : 0;
  }
});

watch(currentSymbol, () => {
  if (orderType.value === 'limit') {
    const pInfo = marketStore.priceMap[currentSymbol.value];
    limitPrice.value = pInfo && pInfo.price ? Number(pInfo.price) : 0;
  }
});

const useStopLoss = ref(false);
const stopLossPrice = ref(0);
const useTakeProfit = ref(false);
const takeProfitPrice = ref(0);
const quantity = ref(0.01);

const currentSymbolInfo = ref<any>(null);

// 获取每手数量
const lotSize = computed(() => {
  const value = currentSymbolInfo.value?.lotSize;
  if (value != null && !isNaN(Number(value))) {
    return Number(value);
  }
  return 1000;
});

// 获取手续费倍数
const feeMultiplier = computed(() => {
  const value = currentSymbolInfo.value?.feeMultiplier;
  if (value != null && !isNaN(Number(value))) {
    return Number(value);
  }
  return 30;
});

// 计算预估保证金 = 买入数量 × 每手数量
const estimatedMargin = computed(() => {
  const qty = Number(quantity.value) || 0;
  if (qty <= 0) return 0;
  const lot = Number(lotSize.value) || 1000;
  return qty * lot;
});

// 计算预估手续费 = 买入数量 × 手续费倍数
const estimatedFee = computed(() => {
  const qty = Number(quantity.value) || 0;
  if (qty <= 0) return 0;
  const multiplier = Number(feeMultiplier.value) || 30;
  return qty * multiplier;
});

const optionDurations = ref<any[]>([]);
const optionTime = ref(60);
const optionAmount = ref('');

const currentOptionProfitRate = computed(() => {
  const duration = optionDurations.value.find(d => d.duration === optionTime.value);
  return duration ? Number(duration.profitRate) : 0.18;
});

const expectedOptionProfit = computed(() => {
  const amount = Number(optionAmount.value);
  if (isNaN(amount) || amount <= 0) return '0.00';
  return (amount * currentOptionProfitRate.value).toFixed(2);
});

const loadOptionDurations = async () => {
  try {
    const res: any = await request.get('/trade/option/durations');
    // 根据后端的返回格式：ResponseEntity.ok(durations) 直接返回了数组，或者包裹在 data/list 中
    const list = Array.isArray(res) ? res : (res && res.list ? res.list : (res && res.data ? res.data : []));
    if (list && list.length > 0) {
      optionDurations.value = list;
      if (!list.find((d: any) => d.duration === optionTime.value)) {
        optionTime.value = list[0].duration;
      }
    }
  } catch (e) {
    console.error(`${localeStore.t('getDurationFailed')}${localeStore.t('timeText')}${localeStore.t('failedText')}`, e);
  }
};

const activeFinancialProduct = ref<any>(null);
const showFinancialPurchase = ref(false);
const financialPurchaseAmount = ref('');

const showFinancialPurchaseModal = (product: any) => {
  activeFinancialProduct.value = product;
  financialPurchaseAmount.value = '';
  showFinancialPurchase.value = true;
};

const setMaxFinancialPurchase = () => {
  if (activeFinancialProduct.value && activeFinancialProduct.value.maxPurchase) {
    financialPurchaseAmount.value = activeFinancialProduct.value.maxPurchase.toString();
  }
};

const submitFinancialPurchase = async () => {
  if (!auth.token) {
    ElMessage.warning(localeStore.t('pleaseLoginFirst'));
    showLoginModal.value = true;
    return;
  }
  
  const amountNum = Number(financialPurchaseAmount.value);
  if (isNaN(amountNum) || amountNum <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validText')}${localeStore.t('subscribe')}${localeStore.t('quantityText')}`);
    return;
  }
  if (activeFinancialProduct.value) {
    if (amountNum < activeFinancialProduct.value.minPurchase) {
      ElMessage.warning(`${localeStore.t('cannotBeLessThanMin')}${localeStore.t('subscribe')}${localeStore.t('quantityText')} ${activeFinancialProduct.value.minPurchase}`);
      return;
    }
    if (amountNum > activeFinancialProduct.value.maxPurchase) {
      ElMessage.warning(`${localeStore.t('cannotBeGreaterThan')}最大${localeStore.t('subscribe')}${localeStore.t('quantityText')} ${activeFinancialProduct.value.maxPurchase}`);
      return;
    }
  }

  try {
    const res: any = await request.post('/financial/purchase', {
      productId: activeFinancialProduct.value.id,
      purchaseAmount: amountNum
    });
    if (res && res.success !== false) {
      ElMessage.success(`${localeStore.t('financialText')}${localeStore.t('subscribe')}${localeStore.t('successText')}`);
      showFinancialPurchase.value = false;
      loadWalletBalances(); // 刷新{{ localeStore.t('balanceText') }}
      wealthTab.value = 'purchased';
      loadFinancialOrders();
    } else {
      let errMsg = res?.message || localeStore.t('subscribeFailed');
      if (errMsg.includes('申购失败:') || errMsg.includes(`${localeStore.t('subscribeFailed2')}:`)) {
        errMsg = errMsg.replace('申购失败: ', '').replace(`${localeStore.t('subscribeFailed2')}: `, '');
      }
      ElMessage.error(errMsg);
    }
  } catch (e: any) {
    let errMsg = e.response?.data?.message || localeStore.t('networkErrorOrNotImplemented');
    if (errMsg.includes('申购失败:') || errMsg.includes(`${localeStore.t('subscribeFailed2')}:`)) {
      errMsg = errMsg.replace('申购失败: ', '').replace(`${localeStore.t('subscribeFailed2')}: `, '');
    }
    ElMessage.error(errMsg);
  }
};
const loanAmount = ref('');
const showCreditLoan = ref(false);
const loanSettings = ref<any[]>([]);
const selectedLoanSettingId = ref<number | string>('');

const currentLoanSetting = computed(() => {
  return loanSettings.value.find(s => s.id === selectedLoanSettingId.value) || null;
});

const loadLoanSettings = async () => {
  try {
    const res: any = await request.get('/loan/settings');
    const list = Array.isArray(res) ? res : (res && res.list ? res.list : (res && res.data ? res.data : []));
    loanSettings.value = list;
    if (list.length > 0) {
      selectedLoanSettingId.value = list[0].id;
    }
  } catch (e) {
    console.error(localeStore.t('getLoanConfigFailed'), e);
  }
};

const financialProducts = ref<any[]>([]);
const purchasedFinancialOrders = ref<any[]>([]);

const loadFinancialOrders = async () => {
  if (!auth.token) return;
  try {
    const res: any = await request.get('/financial/orders');
    purchasedFinancialOrders.value = res.list || res.data || (Array.isArray(res) ? res : []);
  } catch (e) {
    console.error(`${localeStore.t('getText')}${localeStore.t('subscribeList')}${localeStore.t('failedText')}`, e);
  }
};

const loadFinancialProducts = async () => {
  try {
    const res: any = await request.get('/financial/products');
    const list = Array.isArray(res) ? res : (res && res.list ? res.list : (res && res.data ? res.data : []));
    financialProducts.value = list;
  } catch (e) {
    console.error(localeStore.t('getFinancialProductFailed'), e);
  }
};
const showPersonalInfoModal = ref(false);
const personalInfoForm = ref({
  realName: '',
  idNumber: '',
  phone: '',
  address: '',
  idCardFront: '',
  idCardBack: '',
  idCardHand: ''
});


const depositProofInput = ref<HTMLInputElement | null>(null);
const triggerDepositProofUpload = () => {
  depositProofInput.value?.click();
};

const handleImageUpload = async (e: Event, type: string) => {
  const target = e.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;

  const isImage = file.type.startsWith('image/');
  const isLt5M = file.size / 1024 / 1024 < 5;

  if (!isImage) {
    ElMessage.error(`${localeStore.t('onlySupportImageFormat')}!`);
    target.value = '';
    return;
  }
  if (!isLt5M) {
    ElMessage.error(`${localeStore.t('imageSizeCannotExceed')} 5MB!`);
    target.value = '';
    return;
  }

  const formData = new FormData();
  formData.append('file', file);
  try {
    const res: any = await request.post('/upload/image', formData);
    const data = res.data || res;
    if (data && (data.success || data.url)) {
      if (type === 'deposit') {
        depositForm.value.proofImage = data.url;
        ElMessage.success(localeStore.t('voucherUploadSuccess'));
      } else if (type === 'kycFront') {
        personalInfoForm.value.idCardFront = data.url;
        ElMessage.success(localeStore.t('idFrontUploadSuccess'));
      } else if (type === 'kycBack') {
        personalInfoForm.value.idCardBack = data.url;
        ElMessage.success(localeStore.t('idBackUploadSuccess'));
      } else if (type === 'kycHand') {
        personalInfoForm.value.idCardHand = data.url;
        ElMessage.success(localeStore.t('idHandheldUploadSuccess'));
      }
    } else {
      ElMessage.error(data?.message || localeStore.t('uploadFailed'));
    }
  } catch (err: any) {
    ElMessage.error(localeStore.t('uploadFailed'));
  } finally {
    target.value = '';
  }
};





const kycFrontInput = ref<HTMLInputElement | null>(null);
const triggerKycFrontUpload = () => {
  kycFrontInput.value?.click();
};

const kycBackInput = ref<HTMLInputElement | null>(null);
const triggerKycBackUpload = () => {
  kycBackInput.value?.click();
};

const kycHandInput = ref<HTMLInputElement | null>(null);
const triggerKycHandUpload = () => {
  kycHandInput.value?.click();
};

const loanKycFrontInput = ref<HTMLInputElement | null>(null);
const triggerLoanKycFrontUpload = () => {
  loanKycFrontInput.value?.click();
};

const loanKycBackInput = ref<HTMLInputElement | null>(null);
const triggerLoanKycBackUpload = () => {
  loanKycBackInput.value?.click();
};

const loanKycHandInput = ref<HTMLInputElement | null>(null);
const triggerLoanKycHandUpload = () => {
  loanKycHandInput.value?.click();
};

const isKycVerified = ref(false);
const kycStatus = ref('UNVERIFIED'); // 'UNVERIFIED', 'PENDING', 'VERIFIED'

const loadKycStatus = async () => {
  if (!auth.token) return;
  try {
    const res: any = await request.get('/loan/personal-info/status');
    if (res && res.success && res.verified) {
      isKycVerified.value = true;
      kycStatus.value = 'VERIFIED';
    } else {
      isKycVerified.value = false;
      kycStatus.value = res?.status || 'UNVERIFIED';
      // 如果未验证，可以把后端返回的数据填入表单，方便用户修改后重新提交
      if (res && res.data) {
        personalInfoForm.value = {
          realName: res.data.realName || '',
          idNumber: res.data.idNumber || '',
          phone: res.data.phone || '',
          address: res.data.address || '',
          idCardFront: res.data.idCardFront || '',
          idCardBack: res.data.idCardBack || '',
          idCardHand: res.data.idCardHand || ''
        };
      }
    }
  } catch (e) {
    console.error(`${localeStore.t('getText')}${localeStore.t('authText')}${localeStore.t('statusFailed')}`, e);
    isKycVerified.value = false;
    kycStatus.value = 'UNVERIFIED';
  }
};

const submitPersonalInfo = async () => {
  if (!personalInfoForm.value.realName || !personalInfoForm.value.idNumber || !personalInfoForm.value.phone || !personalInfoForm.value.address) {
    ElMessage.warning(localeStore.t('pleaseFillCompletePersonalInfo'));
    return;
  }
  if (!personalInfoForm.value.idCardFront || !personalInfoForm.value.idCardBack || !personalInfoForm.value.idCardHand) {
    ElMessage.warning(localeStore.t('pleaseUploadCompleteIdPhotos'));
    return;
  }
  
  try {
    // 提交{{ localeStore.t('authText') }}信息时{{ localeStore.t('requiredText2') }}使用 FormData，因为后端接收的是 @RequestParam
    const formData = new FormData();
    formData.append('realName', personalInfoForm.value.realName);
    formData.append('idNumber', personalInfoForm.value.idNumber);
    formData.append('phone', personalInfoForm.value.phone);
    formData.append('address', personalInfoForm.value.address);
    
    // 从后端接口看，其实直接提交图片 URL 并不是它期望的，它期望的是 MultipartFile
    // 但是这里我们由于前端使用了 el-upload 已经把图片上传到 /api/upload/image {{ localeStore.t('getText') }}了 URL
    // 我们${localeStore.t('requiredText2')}修改提交方式或者让后端接口兼容 URL 提交。
    // 如果后端接口不支持，这里直接提交原先的 JSON 可能会导致 400/500。
    // 为解决此问题，${localeStore.t('requiredText2')}调整提交的参数结构或者提交方式。
    const res: any = await request.post('/loan/personal-info/submit', null, {
      params: {
        realName: personalInfoForm.value.realName,
        idNumber: personalInfoForm.value.idNumber,
        phone: personalInfoForm.value.phone,
        address: personalInfoForm.value.address,
        idFrontImage: personalInfoForm.value.idCardFront,
        idBackImage: personalInfoForm.value.idCardBack,
        handheldImage: personalInfoForm.value.idCardHand
      }
    });
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('personalInfoSubmitSuccessWaitReview'));
      showPersonalInfoModal.value = false;
      loadKycStatus(); // 更新状态
    } else {
      ElMessage.error(res?.message || localeStore.t('submitFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('networkErrorOrNotImplemented'));
  }
};

const submitPersonalInfoFromKyc = async () => {
  if (!personalInfoForm.value.realName || !personalInfoForm.value.idNumber) {
    ElMessage.warning(localeStore.t('pleaseFillCompletePersonalInfo'));
    return;
  }
  if (!personalInfoForm.value.idCardFront || !personalInfoForm.value.idCardBack || !personalInfoForm.value.idCardHand) {
    ElMessage.warning(localeStore.t('pleaseUploadCompleteIdPhotos'));
    return;
  }
  
  try {
    const res: any = await request.post('/loan/personal-info/submit', null, {
      params: {
        realName: personalInfoForm.value.realName,
        idNumber: personalInfoForm.value.idNumber,
        phone: personalInfoForm.value.phone || '00000000000',
        address: personalInfoForm.value.address || localeStore.t('noneText'),
        idFrontImage: personalInfoForm.value.idCardFront,
        idBackImage: personalInfoForm.value.idCardBack,
        handheldImage: personalInfoForm.value.idCardHand
      }
    });
    if (res && res.success !== false) {
      ElMessage.success(`${localeStore.t('realName2')}${localeStore.t('authText')}${localeStore.t('dataSubmitSuccessWaitReview')}`);
      loadKycStatus(); // 更新页面状态为 PENDING
    } else {
      ElMessage.error(res?.message || localeStore.t('submitFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('submitFailed'));
  }
};

const formatMoney = (v: number | string | undefined | null) => {
  const n = Number(v || 0);
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};

const setMaxLoanAmount = () => {
  if (currentLoanSetting.value && currentLoanSetting.value.maxAmount) {
    loanAmount.value = currentLoanSetting.value.maxAmount.toString();
  } else {
    loanAmount.value = '100000';
  }
};

const totalLoanInterest = computed(() => {
  const amount = Number(loanAmount.value);
  if (isNaN(amount) || amount <= 0 || !currentLoanSetting.value) return '0.00';
  
  const days = Number(currentLoanSetting.value.days || 0);
  const freeDays = Number(currentLoanSetting.value.freeDays || 0);
  const dailyRate = Number(currentLoanSetting.value.dailyRate || 0) / 100;
  
  const interestDays = Math.max(0, days - freeDays);
  return (amount * dailyRate * interestDays).toFixed(2);
});

const submitLoan = async () => {
  if (!auth.token) {
    ElMessage.warning(localeStore.t('pleaseLoginFirst'));
    showLoginModal.value = true;
    return;
  }
  if (!isKycVerified.value) {
    ElMessage.warning(localeStore.t('pleaseCompletePersonalInfoAuthFirst'));
    return;
  }
  const amountNum = Number(loanAmount.value);
  if (isNaN(amountNum) || amountNum <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validText')}${localeStore.t('loanAmount')}`);
    return;
  }
  if (!currentLoanSetting.value) {
    ElMessage.warning(localeStore.t('pleaseSelectLoanTerm'));
    return;
  }
  if (currentLoanSetting.value.minAmount && amountNum < currentLoanSetting.value.minAmount) {
    ElMessage.warning(`${localeStore.t('amountCannotBeLessThan')} ${currentLoanSetting.value.minAmount}`);
    return;
  }
  if (currentLoanSetting.value.maxAmount && amountNum > currentLoanSetting.value.maxAmount) {
    ElMessage.warning(`${localeStore.t('amountText')}${localeStore.t('cannotBeGreaterThan')} ${currentLoanSetting.value.maxAmount}`);
    return;
  }
  try {
    const personalInfoRes: any = await request.get('/loan/personal-info/status');
    if (!personalInfoRes || !personalInfoRes.success || !personalInfoRes.verified || !personalInfoRes.data) {
      ElMessage.warning(`${localeStore.t('getPersonalInfoFailed')}${localeStore.t('authText')}${localeStore.t('infoFailed')}，${localeStore.t('pleaseEnsureCompletedRealNameAuth')}`);
      return;
    }
    const personalInfo = personalInfoRes.data;

    const res: any = await request.post('/loan/apply', {
      amount: amountNum,
      settingId: currentLoanSetting.value.id.toString(),
      days: currentLoanSetting.value.days,
      realName: personalInfo.realName,
      idNumber: personalInfo.idNumber,
      phone: personalInfo.phone || '00000000000',
      address: personalInfo.address || localeStore.t('noneText')
    });
    if (res && res.success !== false && res.data) {
      ElMessage.success(localeStore.t('creditLoanApplySuccess'));
      showCreditLoan.value = false;
      loanAmount.value = '';
      loadWalletBalances(); // 刷新{{ localeStore.t('balanceText') }}
      
      // 打开合同弹窗
      currentLoanRecord.value = res.data;
      showLoanContract.value = true;
    } else {
      ElMessage.error(res?.message || localeStore.t('loanApplyFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('networkErrorOrNotImplemented'));
  }
};

const showYieldList = ref(false);
const currentYieldList = ref<any[]>([]);
const currentYieldStats = ref<any>(null);

const showLoanRecordsModal = ref(false);
const userLoanRecords = ref<any[]>([]);

const openLoanRecords = async () => {
  try {
    const res: any = await request.get('/loan/list');
    if (res && res.success !== false) {
      userLoanRecords.value = res.list || [];
      showLoanRecordsModal.value = true;
    } else {
      ElMessage.error(res?.message || `${localeStore.t('getText')}${localeStore.t('recordText')}${localeStore.t('failedText')}`);
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || `${localeStore.t('getText')}${localeStore.t('recordText')}${localeStore.t('failedText')}`);
  }
};

const repayLoan = async (id: number) => {
  try {
    const res: any = await request.post(`/loan/repay/${id}`);
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('repaySuccess'));
      loadWalletBalances();
      openLoanRecords(); // refresh
    } else {
      ElMessage.error(res?.message || localeStore.t('repayFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('repayFailed'));
  }
};

const viewLoanContract = async (loanId: number) => {
  try {
    const res: any = await request.get(`/loan/${loanId}`);
    if (res && res.success !== false) {
      currentLoanRecord.value = res.data;
      showLoanContract.value = true;
    } else {
      ElMessage.error(res?.message || localeStore.t('getLoanDetailsFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('getLoanDetailsFailed'));
  }
};
const showLoanContract = ref(false);
const showLoanSign = ref(false);
const currentLoanRecord = ref<any>(null);
const signCanvasRef = ref<HTMLCanvasElement | null>(null);
const signSubmitting = ref(false);
const isDrawing = ref(false);
let ctx: CanvasRenderingContext2D | null = null;

const startDraw = (e: MouseEvent | TouchEvent) => {
  if (!signCanvasRef.value) return;
  isDrawing.value = true;
  ctx = signCanvasRef.value.getContext('2d');
  if (!ctx) return;
  const rect = signCanvasRef.value.getBoundingClientRect();
  const x = ('touches' in e) ? (e as any).touches[0].clientX - rect.left : (e as MouseEvent).clientX - rect.left;
  const y = ('touches' in e) ? (e as any).touches[0].clientY - rect.top : (e as MouseEvent).clientY - rect.top;
  ctx.beginPath();
  ctx.moveTo(x, y);
  ctx.lineWidth = 3;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = '#333';
};

const draw = (e: MouseEvent | TouchEvent) => {
  if (!isDrawing.value || !ctx || !signCanvasRef.value) return;
  e.preventDefault();
  const rect = signCanvasRef.value.getBoundingClientRect();
  const x = ('touches' in e) ? (e as any).touches[0].clientX - rect.left : (e as MouseEvent).clientX - rect.left;
  const y = ('touches' in e) ? (e as any).touches[0].clientY - rect.top : (e as MouseEvent).clientY - rect.top;
  ctx.lineTo(x, y);
  ctx.stroke();
};

const endDraw = () => {
  isDrawing.value = false;
  if (ctx) {
    ctx.closePath();
  }
};

const clearSignature = () => {
  if (!signCanvasRef.value) return;
  const context = signCanvasRef.value.getContext('2d');
  if (context) {
    context.clearRect(0, 0, signCanvasRef.value.width, signCanvasRef.value.height);
  }
};

const submitSignature = async () => {
  if (signSubmitting.value || !currentLoanRecord.value || !signCanvasRef.value) return;
  const context = signCanvasRef.value.getContext('2d');
  if (!context) return;

  const imageData = context.getImageData(0, 0, signCanvasRef.value.width, signCanvasRef.value.height);
  const hasSignature = imageData.data.some((value, index) => index % 4 !== 3 && value !== 255);

  if (!hasSignature) {
    ElMessage.warning(localeStore.t('pleaseSignOnCanvasFirst'));
    return;
  }

  signSubmitting.value = true;
  const signatureImage = signCanvasRef.value.toDataURL('image/png');

  try {
    const res: any = await request.post('/loan/sign', {
      loanId: currentLoanRecord.value.id,
      signatureImage: signatureImage
    });
    if (res && res.success) {
      ElMessage.success(localeStore.t('signSuccess'));
      showLoanSign.value = false;
      loadWalletBalances();
      openLoanRecords();
    } else {
      ElMessage.error(res?.message || localeStore.t('signFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('signFailed'));
  } finally {
    signSubmitting.value = false;
  }
};

const openLoanSign = () => {
  showLoanContract.value = false;
  showLoanSign.value = true;
  nextTick(() => {
    if (signCanvasRef.value) {
      // 适配高分屏或固定尺寸
      signCanvasRef.value.width = signCanvasRef.value.offsetWidth;
      signCanvasRef.value.height = signCanvasRef.value.offsetHeight;
      const context = signCanvasRef.value.getContext('2d');
      if (context) {
        context.fillStyle = '#ffffff';
        context.fillRect(0, 0, signCanvasRef.value.width, signCanvasRef.value.height);
      }
    }
  });
};

const showYieldListModal = async (order: any) => {
  try {
    const res: any = await request.get(`/financial/yield/order/${order.id}`);
    if (res && res.success !== false) {
      currentYieldList.value = res.list || [];
      currentYieldStats.value = res.stats || null;
      showYieldList.value = true;
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('getProfitListFailed'));
  }
};

const earlyRedeemOrder = async (order: any) => {
  try {
    await ElMessageBox.confirm(
      `${localeStore.t('confirmDefaultRedeem1')} ${Number(order.penaltyRate || 0).toFixed(2)}% ${localeStore.t('confirmDefaultRedeem2')}。`,
      localeStore.t('defaultRedeem'),
      {
        confirmButtonText: localeStore.t('confirmRedeemBtn'),
        cancelButtonText: localeStore.t('cancelText'),
        type: 'warning',
      }
    );
    
    const res: any = await request.post(`/financial/redeem/${order.id}`);
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('defaultRedeemSuccess'));
      loadFinancialOrders();
      loadWalletBalances();
    } else {
      ElMessage.error(res?.message || localeStore.t('redeemFailed'));
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.message || localeStore.t('redeemFailed'));
    }
  }
};

const showWealth = ref(false);
const wealthTab = ref('mining');

watch(wealthTab, (val) => {
  if (val === 'purchased') {
    loadFinancialOrders();
  }
});

const showUserCenter = ref(false);
const activeUserMenu = ref('assets');

const currentTime = ref('');

// ======================
// 订单相关状态与逻辑
// ======================
const orderSubTab = ref<'positions' | 'pending' | 'history'>('positions');
const positionsData = ref<any[]>([]);
const pendingOrdersData = ref<any[]>([]);
const historyData = ref<any[]>([]);
const totalMargin = ref(0);
// ======================
// 资金与资产逻辑
// ======================
const walletBalance = ref(0);
const walletFrozen = ref(0);
const contractBalance = ref(0);
const contractFrozen = ref(0);
const optionBalance = ref(0);
const optionFrozen = ref(0);
const totalAsset = computed(() => walletBalance.value + contractBalance.value + optionBalance.value + walletFrozen.value + contractFrozen.value + optionFrozen.value);

const loadWalletBalances = async () => {
  if (!auth.token) return;
  try {
    const res: any = await request.get('/user/assets');
    if (res && res.success !== false) {
      walletBalance.value = Number(res.fundBalance || res.balance || 0);
      walletFrozen.value = Number(res.fundFrozen || 0);
      contractBalance.value = Number(res.contractBalance || 0);
      contractFrozen.value = Number(res.contractFrozen || 0);
      optionBalance.value = Number(res.optionBalance || 0);
      optionFrozen.value = Number(res.optionFrozen || 0);
    }
  } catch (e) {
    console.error(localeStore.t('loadAccountAssetsFailed'), e);
  }
};

const loadContractBalance = async () => {
  // 我们已经通过 /user/assets 接口${localeStore.t('getText')}了所有资产信息，这里可以保留作为一个辅助方法或者空方法
};

const calculateContractProfit = (order: any, currentPrice: number): number => {
  if (!order.openPrice || order.openPrice <= 0 || !currentPrice || currentPrice <= 0) {
    return Number(order.profit || 0);
  }
  const quantity = Number(order.quantity || 0);
  if (quantity <= 0) return 0;
  
  let leverage = order.leverage ? Number(order.leverage) : 10;
  let priceDiff = 0;
  if (order.side === 'BUY') {
    priceDiff = currentPrice - order.openPrice;
  } else if (order.side === 'SELL') {
    priceDiff = order.openPrice - currentPrice;
  } else {
    return Number(order.profit || 0);
  }
  return priceDiff * quantity * leverage;
};

const transformContractOrder = (order: any) => {
  const pInfo = marketStore.priceMap[order.symbol];
  const currentPrice = pInfo ? Number(pInfo.price) : 0;
  let leverage = order.leverage ? Number(order.leverage) : 10;
  const calculatedProfit = calculateContractProfit({ ...order, leverage }, currentPrice);
  const displayTime = order.status === 'PENDING' ? (order.createdAt || order.openTime) : (order.openTime || order.createdAt);
  
  return {
    id: order.id,
    symbol: order.symbol,
    type: order.side?.toLowerCase() || 'buy',
    lots: Number(order.quantity || 0),
    openPrice: Number(order.openPrice || 0),
    currentPrice: currentPrice > 0 ? currentPrice : Number(order.currentPrice || order.openPrice || 0),
    closePrice: Number(order.closePrice || 0),
    profit: calculatedProfit,
    margin: Number(order.margin || 0),
    fee: Number(order.fee || 0),
    openTime: formatDateTime(displayTime),
    stopLoss: order.stopLoss ? Number(order.stopLoss) : 0,
    takeProfit: order.takeProfit ? Number(order.takeProfit) : 0,
    status: order.status,
    side: order.side,
    quantity: order.quantity,
    leverage
  };
};

const loadOptionOrders = async () => {
  if (!auth.token) return;
  try {
    let status = 'TRADING'; // 后端创建期权订单的状态是 TRADING 而不是 OPEN
    if (orderSubTab.value === 'pending') status = 'PENDING';
    if (orderSubTab.value === 'history') status = 'CLOSED';

    const res: any = await request.get('/trade/option/orders', { params: { status } });
    
    // 兼容后端可能返回的数组或包含在 list 字段中的格式
    const rawList = Array.isArray(res) ? res : (res && res.list ? res.list : (res && res.data ? res.data : []));

    const orders = rawList.map((order: any) => {
      const pInfo = marketStore.priceMap[order.symbol];
      const currentPrice = pInfo ? Number(pInfo.price) : 0;
      
      // 预估收益
      const amount = Number(order.amount || 0);
      const expectedProfitRate = Number(order.expectedProfitRate || 0.18);
      const expectedProfit = amount * expectedProfitRate;
      
      return {
        id: order.id,
        symbol: order.symbol,
        type: order.side?.toLowerCase() || order.direction?.toLowerCase() || 'buy', // 兼容 optionOrder 的 direction 字段
        amount,
        openPrice: Number(order.openPrice || 0),
        closePrice: Number(order.closePrice || 0),
        currentPrice: currentPrice > 0 ? currentPrice : Number(order.currentPrice || order.openPrice || 0),
        period: order.period || order.duration || 60, // 兼容 optionOrder 的 duration 字段
        expectedProfit,
        profit: Number(order.profit || 0),
        openTime: formatDateTime(order.createdAt || order.openTime),
        status: order.status,
        side: order.side || order.direction
      };
    });

    if (orderSubTab.value === 'positions') {
      positionsData.value = orders;
    } else if (orderSubTab.value === 'pending') {
      pendingOrdersData.value = orders;
    } else if (orderSubTab.value === 'history') {
      historyData.value = orders;
    }
  } catch (e) {
    console.error(localeStore.t('loadDurationOrdersFailed'), e);
  }
};
const loadContractOrders = async () => {
  if (!auth.token) return;
  try {
    let status = 'OPEN';
    if (orderSubTab.value === 'pending') status = 'PENDING';
    if (orderSubTab.value === 'history') status = 'CLOSED';

    const res: any = await request.get('/trade/contract/orders', { params: { status } });
    const orders = (res.list || []).map(transformContractOrder);

    if (orderSubTab.value === 'positions') {
      positionsData.value = orders;
      totalMargin.value = orders.reduce((sum: number, item: any) => sum + item.margin, 0);
    } else if (orderSubTab.value === 'pending') {
      pendingOrdersData.value = orders;
    } else if (orderSubTab.value === 'history') {
      historyData.value = orders;
    }
  } catch (e) {
    console.error(localeStore.t('loadOrdersFailed'), e);
  }
};

const totalProfit = computed(() => {
  return positionsData.value.reduce((sum, item) => sum + (item.profit || 0), 0);
});

const riskRate = computed(() => {
  if (totalMargin.value > 0) {
    return ((contractBalance.value + totalProfit.value) / totalMargin.value) * 100;
  }
  return 0;
});

const currentOrderList = computed(() => {
  if (orderSubTab.value === 'positions') return positionsData.value;
  if (orderSubTab.value === 'pending') return pendingOrdersData.value;
  return historyData.value;
});

const updateOrdersRealTime = () => {
  if (orderSubTab.value === 'positions') {
    positionsData.value = positionsData.value.map(order => {
      const pInfo = marketStore.priceMap[order.symbol];
      const currentPrice = pInfo ? Number(pInfo.price) : 0;
      if (currentPrice > 0) {
        const profit = calculateContractProfit(order, currentPrice);
        return { ...order, currentPrice, profit };
      }
      return order;
    });
  }
};

// ======================
// 平仓、撤单、TP/SL 逻辑
// ======================
const submitContractOrder = async (side: 'BUY' | 'SELL') => {
  if (!auth.token) {
    ElMessage.warning(localeStore.t('pleaseLoginFirst'));
    showLoginModal.value = true;
    return;
  }
  if (!quantity.value || quantity.value <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validText')}${localeStore.t('buyIn')}${localeStore.t('quantityText')}`);
    return;
  }

  const pInfo = marketStore.priceMap[currentSymbol.value];
  const currentPrice = pInfo ? Number(pInfo.price) : 0;
  if (currentPrice <= 0) {
    ElMessage.warning(localeStore.t('currentPriceNotAvailable'));
    return;
  }

  if (orderType.value === 'limit' && (!limitPrice.value || limitPrice.value <= 0)) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validOrderPrice')}`);
    return;
  }

  const params = {
    symbol: currentSymbol.value,
    side,
    type: orderType.value === 'market' ? 'MARKET' : 'LIMIT',
    quantity: quantity.value,
    price: orderType.value === 'limit' ? limitPrice.value : undefined,
    currentPrice: currentPrice, // 添加当前价，后端以此作为市价单的开仓价
    leverage: 10,
    takeProfit: useTakeProfit.value ? takeProfitPrice.value : undefined,
    stopLoss: useStopLoss.value ? stopLossPrice.value : undefined
  };

  try {
    const res: any = await request.post('/trade/contract/order', params);
    if (res && res.success !== false) {
      ElMessage.success(`${side === 'BUY' ? localeStore.t('buyIn') : localeStore.t('sellText')} ${localeStore.t('orderSuccess')}`);
      loadWalletBalances();
      loadContractOrders();
    } else {
      ElMessage.error(parseErrorMsg(res?.message, localeStore.t('orderFailed')));
    }
  } catch (e: any) {
    ElMessage.error(parseErrorMsg(e, localeStore.t('networkError')));
  }
};

const submitOptionOrder = async (direction: 'UP' | 'DOWN') => {
  if (!auth.token) {
    ElMessage.warning(localeStore.t('pleaseLoginFirst'));
    showLoginModal.value = true;
    return;
  }
  
  const amount = Number(optionAmount.value);
  if (isNaN(amount) || amount < 50) {
    ElMessage.warning(`${localeStore.t('tradeText')}${localeStore.t('quantityText')}${localeStore.t('cannotBeLessThan')} 50`);
    return;
  }

  const pInfo = marketStore.priceMap[currentSymbol.value];
  const currentPrice = pInfo ? Number(pInfo.price) : 0;
  if (currentPrice <= 0) {
    ElMessage.warning(localeStore.t('currentPriceNotAvailable'));
    return;
  }

  const params = {
    symbol: currentSymbol.value,
    direction,
    amount,
    currentPrice,
    duration: optionTime.value
  };

  try {
    const res: any = await request.post('/trade/option/order', params);
    if (res && res.success !== false) {
      ElMessage.success(`${direction === 'UP' ? localeStore.t('buyUpText') : localeStore.t('buyDownText')} ${localeStore.t('orderSuccess')}`);
      optionAmount.value = ''; // 重置金额
      loadWalletBalances();
      loadOptionOrders();
    } else {
      ElMessage.error(parseErrorMsg(res?.message, localeStore.t('orderFailed')));
    }
  } catch (e: any) {
    ElMessage.error(parseErrorMsg(e, localeStore.t('networkError')));
  }
};

const showTpSlModal = ref(false);
const activeOrder = ref<any>(null);
const tpSlForm = ref({ stopLoss: 0, takeProfit: 0 });

const openTpSlModal = (order: any) => {
  activeOrder.value = order;
  tpSlForm.value = {
    stopLoss: order.stopLoss || 0,
    takeProfit: order.takeProfit || 0
  };
  showTpSlModal.value = true;
};

const submitTpSl = async () => {
  if (!activeOrder.value) return;
  try {
    const res: any = await request.post(`/trade/contract/order/${activeOrder.value.id}/update-tp-sl`, {
      takeProfit: tpSlForm.value.takeProfit,
      stopLoss: tpSlForm.value.stopLoss
    });
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('tpSlSetSuccess'));
      showTpSlModal.value = false;
      loadContractOrders();
    } else {
      ElMessage.error(res?.message || localeStore.t('setFailed'));
    }
  } catch (e: any) {
    ElMessage.error(parseErrorMsg(e, localeStore.t('networkError')));
  }
};

const closePosition = async (order: any) => {
  try {
    await ElMessageBox.confirm(`${localeStore.t('confirmCloseOrder')} ${order.symbol} ${localeStore.t('orderQuestion')}`, localeStore.t('closeConfirm'), {
      confirmButtonText: localeStore.t('confirmBtnText'),
      cancelButtonText: localeStore.t('cancelText'),
      type: 'warning',
    });
    
    const pInfo = marketStore.priceMap[order.symbol];
    const currentPrice = pInfo ? Number(pInfo.price) : 0;
    if (currentPrice <= 0) {
      ElMessage.warning(`${localeStore.t('notYet')}${localeStore.t('getText')}${localeStore.t('toCurrentPrice')}，${localeStore.t('cannotClosePosition2')}`);
      return;
    }

    const res: any = await request.post(`/trade/contract/order/${order.id}/close`, { closePrice: currentPrice });
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('closePositionSuccess'));
      loadContractOrders();
      loadContractBalance();
    } else {
      ElMessage.error(res?.message || localeStore.t('closePositionFailed'));
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(parseErrorMsg(e, localeStore.t('networkError')));
    }
  }
};

const cancelOrder = async (order: any) => {
  try {
    await ElMessageBox.confirm(`${localeStore.t('confirmCancelOrder')} ${order.symbol} ${localeStore.t('pendingOrderQuestion')}`, localeStore.t('cancelOrderConfirm'), {
      confirmButtonText: localeStore.t('confirmBtnText'),
      cancelButtonText: localeStore.t('cancelText'),
      type: 'warning',
    });
    
    const res: any = await request.post(`/trade/contract/order/${order.id}/cancel`);
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('cancelOrderSuccess'));
      loadContractOrders();
    } else {
      ElMessage.error(res?.message || localeStore.t('cancelOrderFailed'));
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(parseErrorMsg(e, localeStore.t('networkError')));
    }
  }
};

const userMenus = computed(() => [
  { id: 'assets', name: localeStore.t('myAssets') },
  { id: 'deposit', name: localeStore.t('recharge') },
  { id: 'withdraw', name: localeStore.t('withdrawCoin') },
  { id: 'transfer', name: localeStore.t('transfer') },
  { id: 'wallet', name: localeStore.t('wallet') },
  { id: 'kyc', name: localeStore.t('kyc') },
  { id: 'announcement', name: localeStore.t('announcementNotification') },
  { id: 'invite', name: localeStore.t('inviteFriends') },
  { id: 'password', name: localeStore.t('changePassword') },
  { id: 'support', name: localeStore.t('contactSupport') },
  { id: 'logout', name: localeStore.t('logout') },
]);

const updateTime = () => {
  const now = new Date();
  currentTime.value = `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')} ${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`;
};

let timeInterval: any;

const loadCategories = async () => {
  try {
    const res: any = await request.get('/market/categories');
    // 根据后端格式适配，可能直接返回数组，也可能在 data/list 里
    const list = Array.isArray(res) ? res : (res && res.list ? res.list : (res && res.data ? res.data : []));
    if (list && list.length > 0) {
      (marketStore as any).categories = list;
    }
  } catch (e) {
    console.error(localeStore.t('getCategoryFailed'), e);
  }
};

onMounted(async () => {
  await loadCategories();
  await marketStore.fetchSymbols();
  // 当加载完所有 symbols 之后，批量订阅所有的市场${localeStore.t('tradeText')}对，以${localeStore.t('getText')}整个左侧列表的实时价格
  if (marketStore.symbols && marketStore.symbols.length > 0) {
    marketStore.subscribeSymbols(marketStore.symbols);
    // 初始化 currentSymbolInfo
    const initialSymbol = marketStore.symbols.find((s: any) => s.symbol === currentSymbol.value);
    if (initialSymbol) {
      currentSymbolInfo.value = initialSymbol;
    }
  }
  
  loadOptionDurations();
  loadLoanSettings();
  loadFinancialProducts();
  if (auth.token) {
    loadFinancialOrders();
  }

  if (auth.token) {
    await loadWalletBalances();
    await loadContractBalance();
    await loadContractOrders();
    await loadKycStatus();
  }
  
  updateTime();
  timeInterval = setInterval(() => {
    updateTime();
    updateOrdersRealTime();
  }, 1000);
});

watch(orderSubTab, () => {
  if (tradeMode.value === 'contract') {
    loadContractOrders();
  } else {
    loadOptionOrders();
  }
});

onUnmounted(() => {
  if (timeInterval) {
    clearInterval(timeInterval);
  }
  if (registerCountdownTimer.value !== null) {
    clearInterval(registerCountdownTimer.value);
    registerCountdownTimer.value = null;
  }
  if (forgotCountdownTimer.value !== null) {
    clearInterval(forgotCountdownTimer.value);
    forgotCountdownTimer.value = null;
  }
});

const symbols = computed(() => marketStore.symbols);

const filteredSymbols = computed(() => {
  let result = symbols.value;
  if (currentCategory.value) {
    result = result.filter((s: any) => s.category === currentCategory.value);
  }
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    result = result.filter((s: any) => s.symbol.toLowerCase().includes(q));
  }
  return result;
});

const currentSymbolObj = computed(() => {
  return marketStore.priceMap[currentSymbol.value] || null;
});

const getSymbolPrice = (symbol: string) => {
  const p = marketStore.priceMap[symbol];
  return p && p.price ? Number(p.price).toFixed(2) : '0.00';
};

const getSymbolChange = (symbol: string) => {
  const p = marketStore.priceMap[symbol];
  return p && p.changePct24h ? Number(p.changePct24h).toFixed(2) : '0.00';
};

const currentKline = computed(() => {
  const klines = marketStore.klineDataMap[`${currentSymbol.value}_${currentInterval.value}`];
  if (klines && klines.length > 0) {
    const last = klines[klines.length - 1];
    if (last) {
      return {
        open: last.open.toFixed(2),
        high: last.high.toFixed(2),
        low: last.low.toFixed(2),
        close: last.close.toFixed(2)
      };
    }
  }
  return { open: '0.00', high: '0.00', low: '0.00', close: '0.00' };
});

const getPriceColor = (symbol: any) => {
  if (!symbol || !symbol.changePct24h) return 'text-gray-500 dark:text-gray-400';
  return parseFloat(symbol.changePct24h) >= 0 ? 'text-[#8cc63f]' : 'text-[#ff4d4f]';
};

const selectSymbol = (symbol: any) => {
  currentSymbol.value = symbol.symbol;
  currentSymbolInfo.value = symbol;
  // 我们已经在 onMounted 中批量订阅了所有的 symbol，因此这里不${localeStore.t('requiredText2')}再单独订阅
  // 但如果出于某些原因（比如组件销毁重建），可以保留这行
};

// ======================
// 充值 (Deposit) 逻辑
// ======================
const depositTab = ref('digital'); // 'digital' or 'bank'
const depositSettings = ref<any[]>([]);
const selectedDepositSetting = ref<any>(null);
const bankSetting = ref<any>(null);

const depositForm = ref({
  amount: '',
  proofImage: ''
});

const depositRecords = ref<any[]>([]);

const loadDepositRecords = async () => {
  if (!auth.token) return;
  try {
    const res: any = await request.get('/deposit/records');
    if (res && res.success !== false) {
      depositRecords.value = res.list || [];
    }
  } catch (e) {
    console.error(`${localeStore.t('getText')}${localeStore.t('deposit2')}${localeStore.t('recordText')}${localeStore.t('failedText')}`, e);
  }
};

const loadDepositSettings = async () => {
  try {
    const res: any = await request.get('/deposit/settings/list?type=digital');
    if (res && res.success !== false) {
      depositSettings.value = res.list || res.data || [];
      if (depositSettings.value.length > 0) {
        selectedDepositSetting.value = depositSettings.value[0];
      }
    }
    
    const bankRes: any = await request.get('/deposit/settings/bank');
    if (bankRes && bankRes.success !== false && bankRes.hasBank) {
      bankSetting.value = bankRes;
    } else {
      bankSetting.value = null;
    }
  } catch (e) {
    console.error(localeStore.t('getDepositNetworkFailed'), e);
  }
};



const submitDeposit = async () => {
  let type = depositTab.value;
  let network = '';
  let address = '';
  
  if (type === 'digital') {
    if (!selectedDepositSetting.value) {
      ElMessage.warning(localeStore.t('pleaseSelectDepositNetwork'));
      return;
    }
    network = selectedDepositSetting.value.network;
    address = selectedDepositSetting.value.address;
  } else if (type === 'bank') {
    if (!bankSetting.value) {
      ElMessage.warning(`${localeStore.t('currentlyNotSupported')}${localeStore.t('bankCardDeposit')}`);
      return;
    }
    network = bankSetting.value.bankName;
    address = bankSetting.value.bankAccount;
  }
  
  if (!depositForm.value.amount || Number(depositForm.value.amount) <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validDepositAmount')}`);
    return;
  }
  if (!depositForm.value.proofImage) {
    ElMessage.warning(localeStore.t('pleaseUploadVoucher'));
    return;
  }
  
  try {
    const res: any = await request.post('/deposit/submit', {
      type: type,
      network: network,
      address: address,
      amount: Number(depositForm.value.amount),
      proofImage: depositForm.value.proofImage
    });
    
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('depositSubmitWaitReview'));
      depositForm.value.amount = '';
      depositForm.value.proofImage = '';
      // 可以在此处重置回资金页面或拉取${localeStore.t('deposit2')}${localeStore.t('recordText')}
      loadDepositRecords();
    } else {
      ElMessage.error(res?.message || localeStore.t('submitFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('submitFailed'));
  }
};

// ======================
// 提币 (Withdraw) 逻辑
// ======================
const withdrawTab = ref('digital'); // 'digital' or 'bank'
const withdrawForm = ref({
  currency: 'USD',
  address: '',
  amount: '',
  remark: ''
});
const availableCurrencies = ref(['USD', 'USDT', 'BTC', 'ETH']);
const userDigitalAddresses = ref<any[]>([]);
const userBankCards = ref<any[]>([]);
const withdrawRecords = ref<any[]>([]);

const loadWithdrawRecords = async () => {
  if (!auth.token) return;
  try {
    const res: any = await request.get('/withdraw/records');
    if (res && res.success !== false) {
      withdrawRecords.value = res.list || [];
    }
  } catch (e) {
    console.error(`${localeStore.t('getWithdrawFailed')}${localeStore.t('recordText')}${localeStore.t('failedText')}`, e);
  }
};

const loadUserWithdrawAccounts = async () => {
  try {
    const [digitalRes, bankRes]: any[] = await Promise.all([
      request.get('/wallet/digital-addresses'),
      request.get('/wallet/bank-cards')
    ]);
    if (digitalRes && digitalRes.success !== false) {
      userDigitalAddresses.value = digitalRes.list || [];
    }
    if (bankRes && bankRes.success !== false) {
      userBankCards.value = bankRes.list || [];
    }
  } catch (e) {
    console.error(localeStore.t('getWithdrawAccountFailed'), e);
  }
};

const submitWithdraw = async () => {
  if (!withdrawForm.value.currency) {
    ElMessage.warning(localeStore.t('pleaseSelectCurrency'));
    return;
  }
  if (!withdrawForm.value.address) {
    ElMessage.warning(localeStore.t('pleaseSelectWithdrawAddress'));
    return;
  }
  if (!withdrawForm.value.amount || Number(withdrawForm.value.amount) <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validWithdrawAmount')}`);
    return;
  }
  
  if (Number(withdrawForm.value.amount) > walletBalance.value) {
    ElMessage.warning(localeStore.t('balanceInsufficient'));
    return;
  }
  
  let network = '';
  if (withdrawTab.value === 'digital') {
    const matchedAddr = userDigitalAddresses.value.find(a => a.address === withdrawForm.value.address);
    network = matchedAddr ? matchedAddr.network : 'ERC20'; // fallback
  } else {
    const matchedBank = userBankCards.value.find(b => b.recipientAccount === withdrawForm.value.address);
    network = matchedBank ? matchedBank.bankName : 'BANK'; // fallback
  }

  try {
    const res: any = await request.post('/withdraw/submit', {
      type: withdrawTab.value,
      network: network,
      address: withdrawForm.value.address,
      amount: Number(withdrawForm.value.amount),
      remark: withdrawForm.value.remark
    });
    
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('withdrawApplicationSubmittedWaitReview'));
      withdrawForm.value.amount = '';
      withdrawForm.value.remark = '';
      withdrawForm.value.address = '';
      loadWalletBalances();
      loadWithdrawRecords();
    } else {
      ElMessage.error(res?.message || localeStore.t('submitFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('submitFailed'));
  }
};

// ======================
// ${localeStore.t('transferText')} (Transfer) 逻辑
// ======================
const transferForm = ref({
  fromAccount: 'FUND',
  toAccount: 'CONTRACT',
  amount: ''
});
const transferRecords = ref<any[]>([]);

const getAccountName = (accountCode: string) => {
  switch (accountCode) {
    case 'FUND': return `${localeStore.t('fundAccount2')}`;
    case 'CONTRACT': return `${localeStore.t('contractAccount2')}`;
    case 'OPTION': return `${localeStore.t('optionsAccount2')}`;
    default: return accountCode;
  }
};

const loadTransferRecords = async () => {
  try {
    const res: any = await request.get('/transfer/records?page=0&size=20');
    if (res && res.success !== false) {
      transferRecords.value = res.list || [];
    }
  } catch (e) {
    console.error(`${localeStore.t('getTransferFailed')}${localeStore.t('recordText')}${localeStore.t('failedText')}`, e);
  }
};

const getAvailableTransferBalance = () => {
  switch (transferForm.value.fromAccount) {
    case 'FUND':
      return walletBalance.value;
    case 'CONTRACT':
      return contractBalance.value;
    case 'OPTION':
      return optionBalance.value;
    default:
      return 0;
  }
};

const handleTransferAccountChange = () => {
  if (transferForm.value.fromAccount === transferForm.value.toAccount) {
    // 如果选择相同，则自动交换
    swapTransferAccounts();
  }
  // 清空已输入的金额
  transferForm.value.amount = '';
};

const swapTransferAccounts = () => {
  const temp = transferForm.value.fromAccount;
  transferForm.value.fromAccount = transferForm.value.toAccount;
  transferForm.value.toAccount = temp;
  transferForm.value.amount = '';
};

const submitTransfer = async () => {
  if (transferForm.value.fromAccount === transferForm.value.toAccount) {
    ElMessage.warning(localeStore.t('transferAccountsCannotBeSame'));
    return;
  }
  
  const amount = Number(transferForm.value.amount);
  if (!amount || amount <= 0) {
    ElMessage.warning(`${localeStore.t('pleaseEnterText')}${localeStore.t('validText')}${localeStore.t('transferText')}数量`);
    return;
  }
  
  if (amount > getAvailableTransferBalance()) {
    ElMessage.warning(`${localeStore.t('transferText')}${localeStore.t('quantityText')}${localeStore.t('cannotBeGreaterThan')}${localeStore.t('availableText')}${localeStore.t('balanceText')}`);
    return;
  }

  try {
    const res: any = await request.post('/transfer/submit', {
      fromAccount: transferForm.value.fromAccount,
      toAccount: transferForm.value.toAccount,
      amount: amount
    });
    
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('transferSuccess2'));
      transferForm.value.amount = '';
      loadWalletBalances();
      // localeStore.t('transferSuccess2')后跳回资产列表
      activeUserMenu.value = 'assets';
    } else {
      ElMessage.error(res?.message || localeStore.t('transferFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('transferFailed'));
  }
};

// ======================
// 钱包 (Wallet & Binding) 逻辑
// ======================
const showWalletAsset = ref(true);

const showBindBankModal = ref(false);
const editingBankCardId = ref<number | null>(null);
const bindBankForm = ref({
  bankName: '',
  recipientAccount: '',
  recipientName: '',
  bankAddress: '',
  swift: ''
});

const showBindDigitalModal = ref(false);
const editingDigitalId = ref<number | null>(null);
const bindDigitalForm = ref({
  currency: '',
  network: '',
  address: ''
});

const openBindBankModal = (card: any = null) => {
  if (card && card.id) {
    editingBankCardId.value = card.id;
    bindBankForm.value = {
      bankName: card.bankName || '',
      recipientAccount: card.recipientAccount || '',
      recipientName: card.recipientName || '',
      bankAddress: card.bankAddress || '',
      swift: card.swift || ''
    };
  } else {
    editingBankCardId.value = null;
    bindBankForm.value = {
      bankName: '',
      recipientAccount: '',
      recipientName: '',
      bankAddress: '',
      swift: ''
    };
  }
  showBindBankModal.value = true;
};

const openBindDigitalModal = (addr: any = null) => {
  if (addr && addr.id) {
    editingDigitalId.value = addr.id;
    bindDigitalForm.value = {
      currency: addr.currency || '',
      network: addr.network || '',
      address: addr.address || ''
    };
  } else {
    editingDigitalId.value = null;
    bindDigitalForm.value = {
      currency: '',
      network: '',
      address: ''
    };
  }
  showBindDigitalModal.value = true;
};

const submitBindBank = async () => {
  if (!bindBankForm.value.bankName || !bindBankForm.value.recipientAccount || !bindBankForm.value.recipientName) {
    ElMessage.warning(localeStore.t('pleaseFillAllRequiredFields'));
    return;
  }
  try {
    let res: any;
    if (editingBankCardId.value) {
      res = await request.put(`/wallet/bank-cards/${editingBankCardId.value}`, {
        currency: 'CNY',
        bankName: bindBankForm.value.bankName,
        recipientAccount: bindBankForm.value.recipientAccount,
        recipientName: bindBankForm.value.recipientName,
        bankAddress: bindBankForm.value.bankAddress,
        swift: bindBankForm.value.swift
      });
    } else {
      res = await request.post('/wallet/bank-cards', {
        currency: 'CNY',
        bankName: bindBankForm.value.bankName,
        recipientAccount: bindBankForm.value.recipientAccount,
        recipientName: bindBankForm.value.recipientName,
        bankAddress: bindBankForm.value.bankAddress,
        swift: bindBankForm.value.swift
      });
    }
    if (res && res.success !== false) {
      ElMessage.success(editingBankCardId.value ? localeStore.t('modifySuccess') : localeStore.t('bankCardBindSuccess'));
      showBindBankModal.value = false;
      loadUserWithdrawAccounts(); // 刷新出金页面的选择项
    } else {
      ElMessage.error(res?.message || localeStore.t('bindFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('bindFailed'));
  }
};

const submitBindDigital = async () => {
  if (!bindDigitalForm.value.currency || !bindDigitalForm.value.network || !bindDigitalForm.value.address) {
    ElMessage.warning(localeStore.t('pleaseFillAllRequiredFields'));
    return;
  }
  try {
    let res: any;
    if (editingDigitalId.value) {
      res = await request.put(`/wallet/digital-addresses/${editingDigitalId.value}`, bindDigitalForm.value);
    } else {
      res = await request.post('/wallet/digital-addresses', bindDigitalForm.value);
    }
    if (res && res.success !== false) {
      ElMessage.success(editingDigitalId.value ? localeStore.t('modifySuccess') : `${localeStore.t('digitalText2')}${localeStore.t('currency')}${localeStore.t('addressBindSuccess')}`);
      showBindDigitalModal.value = false;
      loadUserWithdrawAccounts(); // 刷新出金页面的选择项
    } else {
      ElMessage.error(res?.message || localeStore.t('bindFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('bindFailed'));
  }
};

// ======================
// 更多功能逻辑 (邀${localeStore.t('pleaseText')}, 密码, 反馈, 公告)
// ======================
const announcements = ref<any[]>([]);

const customerServiceLink = ref('')
const complaintEmail = ref('')
const loadCustomerServiceLink = async () => {
  try {
    const res: any = await request.get('/user/customer-service/link')
    if (res && res.link) {
      let url = res.link.trim()
      if (!url.startsWith('http://') && !url.startsWith('https://')) {
        url = 'https://' + url
      }
      customerServiceLink.value = url
    }
  } catch (e) {
    console.error('Failed to load customer service link:', e)
  }
  
  try {
    const res: any = await request.get('/user/complaint/email')
    if (res && res.email) {
      complaintEmail.value = res.email.trim()
    }
  } catch (e) {
    console.error('Failed to load complaint email:', e)
  }
}

const loadAnnouncements = async () => {
  try {
    const res: any = await request.get('/user/announcements');
    if (res && res.success !== false) {
      const allAnnouncements = res.announcements || res.data || [];
      // 过滤出当前语言的公告
      announcements.value = allAnnouncements.filter((item: any) => item.language === localeStore.locale);
    }
  } catch (e) {
    console.error(localeStore.t('getNoticeFailed'), e);
  }
};

const inviteLink = computed(() => {
  const origin = window.location.origin;
  const uid = auth.user?.id || '8959285729';
  return `${origin}/#/register?inviteCode=${uid}`;
});

const copyToClipboard = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(localeStore.t('copySuccess2'));
  } catch (err) {
    ElMessage.error(localeStore.t('copyFailedSelectManually'));
  }
};

const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});

const submitPasswordChange = async () => {
  if (!passwordForm.value.oldPassword || !passwordForm.value.newPassword || !passwordForm.value.confirmPassword) {
    ElMessage.warning(localeStore.t('pleaseFillCompletePasswordInfo'));
    return;
  }
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    ElMessage.warning(`${localeStore.t('twoInputs')}${localeStore.t('newPassword2')}${localeStore.t('inconsistent')}`);
    return;
  }
  try {
    const res: any = await request.post('/user/password/change', {
      oldPassword: passwordForm.value.oldPassword,
      newPassword: passwordForm.value.newPassword
    });
    if (res && res.success !== false) {
      ElMessage.success(localeStore.t('passwordModifySuccessReLogin'));
      auth.logout();
      showLoginModal.value = true;
      showUserCenter.value = false;
    } else {
      ElMessage.error(res?.message || localeStore.t('passwordModifyFailed'));
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || localeStore.t('passwordModifyFailed'));
  }
};



// watch for user menu actions
watch(activeUserMenu, (val) => {
  if (val === 'logout') {
    auth.logout();
    showLoginModal.value = true;
    showUserCenter.value = false;
  } else if (val === 'deposit') {
    loadDepositSettings();
    loadDepositRecords();
  } else if (val === 'withdraw' || val === 'wallet') {
    loadUserWithdrawAccounts();
    if (val === 'withdraw') {
      loadWithdrawRecords();
    }
  } else if (val === 'transfer') {
    loadTransferRecords();
  } else if (val === 'announcement') {
    loadAnnouncements();
  } else if (val === 'support') {
    loadCustomerServiceLink();
  }
});
</script>

<style>
/* 覆盖 Element Plus 样式以匹配 UI */
.custom-dialog .el-dialog__header {
  border-bottom: 1px solid #f0f0f0;
  margin-right: 0;
  padding: 20px 24px;
}
.custom-dialog .el-dialog__title {
  font-weight: bold;
  color: #333;
}
.custom-dialog .el-dialog__body {
  padding: 20px 24px;
}
.el-tabs__item.is-active {
  color: #8cc63f !important;
  font-weight: bold;
}
.el-tabs__active-bar {
  background-color: #8cc63f !important;
  height: 3px !important;
  border-radius: 3px;
}
.el-tabs__item:hover {
  color: #7ab036 !important;
}

/* 自定义滚动条 */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #e5e7eb;
  border-radius: 3px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #d1d5db;
}

/* 自定义 Element Plus 组件样式 */
.custom-search .el-input__wrapper {
  box-shadow: none !important;
  background-color: #f9fafb;
  border-radius: 8px;
}
.custom-search .el-input__wrapper.is-focus {
  background-color: #fff;
  box-shadow: 0 0 0 1px #8cc63f inset !important;
}

.custom-select .el-input__wrapper {
  box-shadow: 0 0 0 1px #e5e7eb inset !important;
  border-radius: 8px;
  padding: 4px 12px;
}
.custom-select .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px #8cc63f inset !important;
}

.custom-input-number .el-input__wrapper {
  box-shadow: 0 0 0 1px #e5e7eb inset !important;
  border-radius: 8px;
}
.custom-input-number.is-controls-right .el-input__wrapper {
  padding-left: 12px;
  padding-right: 40px;
}
.custom-input-number .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px #8cc63f inset !important;
}
.custom-input-number .el-input-number__increase,
.custom-input-number .el-input-number__decrease {
  background-color: #f9fafb;
  border-color: #e5e7eb;
}
.custom-input-number .el-input-number__increase:hover,
.custom-input-number .el-input-number__decrease:hover {
  color: #8cc63f;
}

.custom-input-large .el-input__wrapper {
  box-shadow: 0 0 0 1px #e5e7eb inset !important;
  border-radius: 8px;
  padding: 8px 12px;
}
.custom-input-large .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px #8cc63f inset !important;
}
</style>