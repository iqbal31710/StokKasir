package com.example.stokkasir

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Entity(tableName="products")
data class Product(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val barcode:String="",
    val name:String,
    val category:String,
    val unit:String="pcs",
    val costPrice:Long=0,
    val sellPrice:Long=0,
    val stock:Int=0,
    val minStock:Int=5,
    val note:String="",
    val photoUri:String?=null
)

@Entity(tableName="transactions")
data class StockTx(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val invoice:String,
    val productId:Long,
    val productName:String,
    val type:String, // SALE / IN / ADJUST
    val qty:Int,
    val price:Long,
    val total:Long,
    val timestamp:String
)

@Dao
interface AppDao {
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun products():Flow<List<Product>>
    @Query("SELECT * FROM products WHERE name LIKE '%'||:q||'%' OR category LIKE '%'||:q||'%' OR barcode LIKE '%'||:q||'%' ORDER BY name")
    fun search(q:String):Flow<List<Product>>
    @Query("SELECT * FROM products WHERE barcode=:code LIMIT 1")
    suspend fun byBarcode(code:String):Product?
    @Insert suspend fun insertProduct(p:Product):Long
    @Update suspend fun updateProduct(p:Product)
    @Delete suspend fun deleteProduct(p:Product)
    @Query("UPDATE products SET stock=stock+:delta WHERE id=:id")
    suspend fun changeStock(id:Long,delta:Int)
    @Insert suspend fun insertTx(t:StockTx)
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun transactions():Flow<List<StockTx>>
}

@Database(entities=[Product::class,StockTx::class],version=1,exportSchema=false)
abstract class AppDb:RoomDatabase(){
    abstract fun dao():AppDao
    companion object {
        @Volatile private var INSTANCE:AppDb?=null
        fun get(c:android.content.Context):AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(c.applicationContext,AppDb::class.java,"stokkasir_v3.db").build().also{INSTANCE=it}
        }
    }
}

class MainVM(private val dao:AppDao):ViewModel(){
    val products=dao.products()
    val txs=dao.transactions()
    fun search(q:String)=if(q.isBlank())products else dao.search(q)
    fun add(p:Product)=viewModelScope.launch{dao.insertProduct(p)}
    fun update(p:Product)=viewModelScope.launch{dao.updateProduct(p)}
    fun delete(p:Product)=viewModelScope.launch{dao.deleteProduct(p)}
    fun receive(p:Product,n:Int)=move(p,n,"IN")
    fun sell(p:Product,n:Int)=if(n>0&&p.stock>=n)move(p,-n,"SALE")
    private fun move(p:Product,delta:Int,type:String)=viewModelScope.launch{
        dao.changeStock(p.id,delta)
        dao.insertTx(StockTx(
            invoice="STK-"+System.currentTimeMillis().toString().takeLast(8),
            productId=p.id,productName=p.name,type=type,qty=kotlin.math.abs(delta),
            price=if(type=="SALE")p.sellPrice else p.costPrice,
            total=(if(type=="SALE")p.sellPrice else p.costPrice)*kotlin.math.abs(delta),
            timestamp=LocalDateTime.now().toString()
        ))
    }
    fun checkout(cart:List<Pair<Product,Int>>, onDone:()->Unit)=viewModelScope.launch{
        val invoice="INV-"+System.currentTimeMillis().toString().takeLast(8)
        val now=LocalDateTime.now().toString()
        cart.forEach{(p,q)->
            dao.changeStock(p.id,-q)
            dao.insertTx(StockTx(invoice,p.id,p.name,"SALE",q,p.sellPrice,p.sellPrice*q,now))
        }
        onDone()
    }
}

fun money(v:Long)=NumberFormat.getCurrencyInstance(Locale("id","ID")).format(v).replace(",00","")
fun fmtDate(s:String)=s.replace("T"," ").take(16)

class MainActivity:ComponentActivity(){
    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        setContent { StokKasirApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StokKasirApp(){
    val context=androidx.compose.ui.platform.LocalContext.current
    val db=remember{AppDb.get(context)}
    val vm:MainVM=viewModel(factory=object:androidx.lifecycle.ViewModelProvider.Factory{
        override fun <T:ViewModel> create(c:Class<T>):T=MainVM(db.dao()) as T
    })
    var page by remember{mutableIntStateOf(0)}
    var query by remember{mutableStateOf("")}
    var addDialog by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<Product?>(null)}
    val ps by vm.search(query).collectAsState(initial=emptyList())
    val txs by vm.txs.collectAsState(initial=emptyList())

    Scaffold(
        topBar={TopAppBar(
            title={Text(when(page){0->"StokKasir";1->"Kasir";2->"Laporan";else->"Tentang"})},
            actions={
                if(page==0)IconButton({addDialog=true}){Icon(Icons.Default.Add,"Tambah barang")}
            })},
        bottomBar={NavigationBar{
            NavigationBarItem(page==0,{page=0},{Icon(Icons.Default.Inventory,null)},"Stok")
            NavigationBarItem(page==1,{page=1},{Icon(Icons.Default.PointOfSale,null)},"Kasir")
            NavigationBarItem(page==2,{page=2},{Icon(Icons.Default.Assessment,null)},"Laporan")
            NavigationBarItem(page==3,{page=3},{Icon(Icons.Default.Settings,null)},"Info")
        }}
    ){pad->Column(Modifier.padding(pad).padding(12.dp)){
        when(page){
            0->StockPage(ps,query,{query=it},{selected=it},{addDialog=true})
            1->CashierPage(ps,vm)
            2->ReportPage(txs)
            else->InfoPage()
        }
    }}
    if(addDialog) ProductDialog(null,{addDialog=false}){vm.add(it);addDialog=false}
    selected?.let{p-> ProductDialog(p,{selected=null},{vm.update(it);selected=null}) }
}

@Composable
fun HeaderCard(txs:List<StockTx>,ps:List<Product>){
    val today=LocalDate.now().toString()
    val sales=txs.filter{it.type=="SALE"&&it.timestamp.startsWith(today)}
    val low=ps.count{it.stock<=it.minStock}
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){
            Column(Modifier.weight(1f)){Text("Omzet hari ini",style=MaterialTheme.typography.labelLarge);Text(money(sales.sumOf{it.total}),fontWeight=FontWeight.Bold)}
            Column(Modifier.weight(1f)){Text("Terjual",style=MaterialTheme.typography.labelLarge);Text("${sales.sumOf{it.qty}} item",fontWeight=FontWeight.Bold)}
            Column(Modifier.weight(1f)){Text("Menipis",style=MaterialTheme.typography.labelLarge);Text("$low barang",fontWeight=FontWeight.Bold)}
        }
    }
}

@Composable
fun StockPage(ps:List<Product>,q:String,onQ:(String)->Unit,onEdit:(Product)->Unit,onAdd:()->Unit){
    OutlinedTextField(q,onQ,Modifier.fillMaxWidth(),singleLine=true,label={Text("Cari nama, kategori, atau barcode")},leadingIcon={Icon(Icons.Default.Search,null)})
    Spacer(Modifier.height(10.dp))
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
        items(ps,key={it.id}){p->
            Card(Modifier.fillMaxWidth().clickable{onEdit(p)}){
                Column(Modifier.padding(14.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(p.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text("${p.category} • ${p.unit}")
                            if(p.barcode.isNotBlank())Text("Barcode: ${p.barcode}",style=MaterialTheme.typography.labelSmall)
                        }
                        Text(money(p.sellPrice),fontWeight=FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Stok ${p.stock}${if(p.stock<=p.minStock)" • STOK MENIPIS" else ""}")
                    if(p.note.isNotBlank())Text(p.note,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun CashierPage(ps:List<Product>,vm:MainVM){
    var q by remember{mutableStateOf("")}
    var cart by remember{mutableStateOf(listOf<Pair<Product,Int>>())}
    var paid by remember{mutableStateOf("")}
    val found=ps.filter{it.name.contains(q,true)||it.category.contains(q,true)||it.barcode.contains(q,true)}
    val total=cart.sumOf{it.first.sellPrice*it.second}
    Column{
        OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Cari barang")},leadingIcon={Icon(Icons.Default.Search,null)})
        Spacer(Modifier.height(6.dp))
        if(q.isNotBlank()) LazyColumn(Modifier.heightIn(max=190.dp)){items(found){p->
            ListItem(headlineContent={Text(p.name)},supportingContent={Text("${money(p.sellPrice)} • stok ${p.stock}")},
                trailingContent={Button(enabled=p.stock>0,onClick={
                    val old=cart.find{it.first.id==p.id}?.second?:0
                    if(old<p.stock)cart=cart.filterNot{it.first.id==p.id}+(p to old+1)
                }){Text("+")}})
        }}
        Text("Keranjang",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        LazyColumn(Modifier.weight(1f)){
            items(cart){(p,n)->
                ListItem(headlineContent={Text(p.name)},supportingContent={Text("$n x ${money(p.sellPrice)} = ${money(p.sellPrice*n)}")},
                    trailingContent={IconButton({cart=cart.filterNot{it.first.id==p.id}}){Icon(Icons.Default.Delete,"Hapus")}})
            }
        }
        HorizontalDivider()
        Text("TOTAL ${money(total)}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        OutlinedTextField(paid,{paid=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Uang dibayar")})
        val change=(paid.toLongOrNull()?:0)-total
        Text(if(change>=0)"Kembalian ${money(change)}" else "Kurang ${money(-change)}")
        Button(enabled=cart.isNotEmpty()&&change>=0,onClick={
            vm.checkout(cart){cart=emptyList();paid="";q=""}
        },Modifier.fillMaxWidth()){Text("BAYAR & SELESAIKAN")}
    }
}

@Composable
fun ReportPage(txs:List<StockTx>){
    val month=LocalDate.now().toString().substring(0,7)
    val m=txs.filter{it.timestamp.startsWith(month)}
    val sales=m.filter{it.type=="SALE"}
    val incoming=m.filter{it.type=="IN"}
    Column{
        Text("Laporan $month",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Omzet: ${money(sales.sumOf{it.total})}")
        Text("Penjualan: ${sales.sumOf{it.qty}} item")
        Text("Barang masuk: ${incoming.sumOf{it.qty}} item")
        Text("Transaksi: ${sales.map{it.invoice}.distinct().size}")
        Spacer(Modifier.height(10.dp))
        Text("Riwayat",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        LazyColumn{items(m){t->
            ListItem(headlineContent={Text("${if(t.type=="SALE")"PENJUALAN" else "BARANG MASUK"} • ${t.productName}")},
                supportingContent={Text("${t.qty} ${money(t.total)} • ${fmtDate(t.timestamp)}")})
        }}
    }
}

@Composable
fun InfoPage(){
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("StokKasir V3",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Aplikasi stok dan kasir offline untuk toko kecil/UMKM.")
        Text("• Data barang dan transaksi disimpan di HP.")
        Text("• Stok berkurang otomatis saat transaksi.")
        Text("• Tanggal dan jam memakai perangkat.")
        Text("• Pencarian mendukung nama, kategori, dan barcode.")
        Text("• Batas stok minimum memberi tanda stok menipis.")
        Text("Versi 3.0")
    }
}

@Composable
fun ProductDialog(existing:Product?,close:()->Unit,save:(Product)->Unit){
    var name by remember{mutableStateOf(existing?.name?:"")}
    var cat by remember{mutableStateOf(existing?.category?:"")}
    var barcode by remember{mutableStateOf(existing?.barcode?:"")}
    var unit by remember{mutableStateOf(existing?.unit?:"pcs")}
    var cost by remember{mutableStateOf(existing?.costPrice?.toString()?:"")}
    var sell by remember{mutableStateOf(existing?.sellPrice?.toString()?:"")}
    var stock by remember{mutableStateOf(existing?.stock?.toString()?:"")}
    var min by remember{mutableStateOf(existing?.minStock?.toString()?:"5")}
    var note by remember{mutableStateOf(existing?.note?:"")}
    AlertDialog(onDismissRequest=close,title={Text(if(existing==null)"Tambah Barang" else "Edit Barang")},text={
        Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
            OutlinedTextField(name,{name=it},label={Text("Nama barang")},singleLine=true)
            OutlinedTextField(cat,{cat=it},label={Text("Kategori")},singleLine=true)
            OutlinedTextField(barcode,{barcode=it},label={Text("Barcode (opsional)")},singleLine=true)
            OutlinedTextField(unit,{unit=it},label={Text("Satuan")},singleLine=true)
            OutlinedTextField(cost,{cost=it},label={Text("Harga modal")},singleLine=true)
            OutlinedTextField(sell,{sell=it},label={Text("Harga jual")},singleLine=true)
            OutlinedTextField(stock,{stock=it},label={Text(if(existing==null)"Stok awal" else "Stok saat ini")},singleLine=true)
            OutlinedTextField(min,{min=it},label={Text("Batas stok menipis")},singleLine=true)
            OutlinedTextField(note,{note=it},label={Text("Keterangan")})
        }
    },confirmButton={Button(enabled=name.isNotBlank(),onClick={
        save(Product(id=existing?.id?:0,barcode=barcode,name=name,category=cat,unit=unit,
            costPrice=cost.toLongOrNull()?:0,sellPrice=sell.toLongOrNull()?:0,
            stock=stock.toIntOrNull()?:0,minStock=min.toIntOrNull()?:5,note=note,photoUri=existing?.photoUri))
    }){Text("Simpan")}},dismissButton={TextButton(close){Text("Batal")}})
}
