#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
#
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.11.0
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  : ${repo:=https://repo1.maven.org/maven2}
  if which fetch >/dev/null 2>&1
  then fetch_cmd="fetch -aAqo"
  else fetch_cmd="curl -sSfo"
  fi
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  if ! $fetch_cmd "$local_repo"/"$artifact"~ "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    exit 1
  fi
  case "$(openssl dgst -sha256 -hex < "$local_repo"/"$artifact"~)" in
  *90ba683ba3819c6274e5fdb25513bc526bf8aba3d54736dee3bf0d1b7ac00a07*)
    mv -f "$local_repo"/"$artifact"~ "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/

///MAIN=Rc522Kt

///DEP=com.pi4j:pi4j-core:1.1

import com.pi4j.wiringpi.Gpio
import com.pi4j.wiringpi.Spi
import java.io.FileReader

/**
 * Original Java code created by Liang on 2016/3/17, originated from Python RC522
 */
class RaspRC522(
        private val speed: Int = 500000,
        private val NRSTPD: Int = 22 // RST pin number
) {
    init {
        if (speed < 500000 || speed > 32000000) {
            error("speed out of range")
        }
        RC522_Init()
    }

    private val SPI_Channel = 0
    private val MAX_LEN = 16

    fun debug(msg: String) {
        println(msg)
    }

    fun RC522_Init() {
        Gpio.wiringPiSetup() // Enable wiringPi pin schema
        val fd = Spi.wiringPiSPISetup(SPI_Channel, speed)
        if (fd <= -1) {
            error("failed to set up SPI communication")
        } else {
            debug("successfully loaded SPI communication")
        }
        Gpio.pinMode(NRSTPD, Gpio.OUTPUT)
        Gpio.digitalWrite(NRSTPD, Gpio.HIGH)
        Reset()
        Write_RC522(TModeReg, 0x8D.toByte())
        Write_RC522(TPrescalerReg, 0x3E.toByte())
        Write_RC522(TReloadRegL, 30.toByte())
        Write_RC522(TReloadRegH, 0.toByte())
        Write_RC522(TxAutoReg, 0x40.toByte())
        Write_RC522(ModeReg, 0x3D.toByte())
        AntennaOn()
    }

    private fun Reset() {
        Write_RC522(CommandReg, PCD_RESETPHASE)
    }

    private fun Write_RC522(address: Byte, value: Byte) {
        val data = ByteArray(2)
        data[0] = (address.toInt() shl 1 and 0x7E).toByte()
        data[1] = value
        val result = Spi.wiringPiSPIDataRW(SPI_Channel, data)
        if (result == -1)
            error("device write error, address=$address, value=$value")
    }

    private fun Read_RC522(address: Byte): Byte {
        val data = ByteArray(2)
        data[0] = (address.toInt() shl 1 and 0x7E or 0x80).toByte()
        data[1] = 0
        val result = Spi.wiringPiSPIDataRW(SPI_Channel, data)
        if (result == -1)
            error("device read error, address=$address")
        return data[1]
    }

    private fun SetBitMask(address: Byte, mask: Byte) {
        val value = Read_RC522(address)
        Write_RC522(address, (value.toInt() or mask.toInt()).toByte())
    }

    private fun ClearBitMask(address: Byte, mask: Byte) {
        val value = Read_RC522(address)
        Write_RC522(address, (value.toInt() and mask.toInt().inv()).toByte())
    }

    private fun AntennaOn() {
        val value = Read_RC522(TxControlReg)
        //   if((value & 0x03) != 0x03)
        SetBitMask(TxControlReg, 0x03.toByte())
    }

    private fun AntennaOff() {
        ClearBitMask(TxControlReg, 0x03.toByte())
    }

    //back_data-最长不超过Length=16;
    //back_data-返回数据
    //back_bits-返回比特数
    //backLen-返回字节数
    private fun Write_Card(command: Byte,
                           data: ByteArray,
                           dataLen: Int,
                           back_data: ByteArray,
                           back_bits: IntArray,
                           backLen: IntArray): Int {
        var status = MI_ERR
        var irq: Byte = 0
        var irq_wait: Byte = 0
        var lastBits: Byte = 0
        var n = 0
        var i = 0

        backLen[0] = 0
        if (command == PCD_AUTHENT) {
            irq = 0x12
            irq_wait = 0x10
        } else if (command == PCD_TRANSCEIVE) {
            irq = 0x77
            irq_wait = 0x30
        }

        Write_RC522(CommIEnReg, (irq.toInt() or 0x80).toByte())
        ClearBitMask(CommIrqReg, 0x80.toByte())
        SetBitMask(FIFOLevelReg, 0x80.toByte())

        Write_RC522(CommandReg, PCD_IDLE)

        i = 0
        while (i < dataLen) {
            Write_RC522(FIFODataReg, data[i])
            i++
        }

        Write_RC522(CommandReg, command)
        if (command == PCD_TRANSCEIVE)
            SetBitMask(BitFramingReg, 0x80.toByte())

        i = 2000
        while (true) {
            n = Read_RC522(CommIrqReg).toInt()
            i--
            if (i == 0 || n and 0x01 > 0 || n and irq_wait.toInt() > 0) {
                //System.out.println("Write_Card i="+i+",n="+n);
                break
            }
        }
        ClearBitMask(BitFramingReg, 0x80.toByte())

        if (i != 0) {
            if (Read_RC522(ErrorReg).toInt() and 0x1B == 0x00) {
                status = MI_OK
                if (n and irq.toInt() and 0x01 > 0)
                    status = MI_NOTAGERR
                if (command == PCD_TRANSCEIVE) {
                    n = Read_RC522(FIFOLevelReg).toInt()
                    lastBits = (Read_RC522(ControlReg).toInt() and 0x07).toByte()
                    if (lastBits.toInt() != 0)
                        back_bits[0] = (n - 1) * 8 + lastBits
                    else
                        back_bits[0] = n * 8

                    if (n == 0) n = 1
                    if (n > this.MAX_LEN) n = this.MAX_LEN
                    backLen[0] = n
                    i = 0
                    while (i < n) {
                        back_data[i] = Read_RC522(FIFODataReg)
                        i++
                    }
                }
            } else
                status = MI_ERR
        }
        return status
    }

    fun Request(req_mode: Byte, back_bits: IntArray): Int {
        var status: Int
        val tagType = ByteArray(1)
        val data_back = ByteArray(16)
        val backLen = IntArray(1)

        Write_RC522(BitFramingReg, 0x07.toByte())

        tagType[0] = req_mode
        back_bits[0] = 0
        status = Write_Card(PCD_TRANSCEIVE, tagType, 1, data_back, back_bits, backLen)
        if (status != MI_OK || back_bits[0] != 0x10) {
            //System.out.println("status="+status+",back_bits[0]="+back_bits[0]);
            status = MI_ERR
        }

        return status
    }

    //Anti-collision detection.
    //Returns tuple of (error state, tag ID).
    //back_data-5字节 4字节tagid+1字节校验
    fun AntiColl(back_data: ByteArray): Int {
        var status: Int
        val serial_number = ByteArray(2)   //2字节命令
        var serial_number_check = 0
        val backLen = IntArray(1)
        val back_bits = IntArray(1)
        var i: Int

        Write_RC522(BitFramingReg, 0x00.toByte())
        serial_number[0] = PICC_ANTICOLL
        serial_number[1] = 0x20
        status = Write_Card(PCD_TRANSCEIVE, serial_number, 2, back_data, back_bits, backLen)
        if (status == MI_OK) {
            if (backLen[0] == 5) {
                i = 0
                while (i < 4) {
                    serial_number_check = serial_number_check xor back_data[i].toInt()
                    i++
                }
                if (serial_number_check != back_data[4].toInt()) {
                    status = MI_ERR
                    println("check error")
                }
            } else {
                status = MI_OK
                println("backLen[0]=" + backLen[0])
            }
        }
        return status
    }

    //CRC值放在data[]最后两字节
    private fun Calculate_CRC(data: ByteArray) {
        var i: Int
        var n: Int
        ClearBitMask(DivIrqReg, 0x04.toByte())
        SetBitMask(FIFOLevelReg, 0x80.toByte())

        i = 0
        while (i < data.size - 2) {
            Write_RC522(FIFODataReg, data[i])
            i++
        }
        Write_RC522(CommandReg, PCD_CALCCRC)
        i = 255
        while (true) {
            n = Read_RC522(DivIrqReg).toInt()
            i--
            if (i == 0 || n and 0x04 > 0)
                break
        }
        data[data.size - 2] = Read_RC522(CRCResultRegL)
        data[data.size - 1] = Read_RC522(CRCResultRegM)
    }

    //uid-5字节数组,存放序列号
    //返值是大小
    fun Select_Tag(uid: ByteArray): Int {
        val status: Int
        val data = ByteArray(9)
        val back_data = ByteArray(this.MAX_LEN)
        val back_bits = IntArray(1)
        val backLen = IntArray(1)
        var i: Int
        var j: Int

        data[0] = PICC_SElECTTAG
        data[1] = 0x70
        i = 0
        j = 2
        while (i < 5) {
            data[j] = uid[i]
            i++
            j++
        }
        Calculate_CRC(data)

        status = Write_Card(PCD_TRANSCEIVE, data, 9, back_data, back_bits, backLen)
        return if (status == MI_OK && back_bits[0] == 0x18)
            back_data[0].toInt()
        else
            0
    }

    //Authenticates to use specified block address. Tag must be selected using select_tag(uid) before auth.
    //auth_mode-RFID.auth_a or RFID.auth_b
    //block_address- used to authenticate
    //key-list or tuple(数组) with six bytes key
    //uid-list or tuple with four bytes tag ID
    fun Auth_Card(auth_mode: Byte, block_address: Byte, key: ByteArray, uid: ByteArray): Int {
        var status: Int
        val data = ByteArray(12)
        val back_data = ByteArray(this.MAX_LEN)
        val back_bits = IntArray(1)
        val backLen = IntArray(1)
        var i: Int
        var j: Int

        data[0] = auth_mode
        data[1] = block_address
        i = 0
        j = 2
        while (i < 6) {
            data[j] = key[i]
            i++
            j++
        }
        i = 0
        j = 8
        while (i < 4) {
            data[j] = uid[i]
            i++
            j++
        }

        status = Write_Card(PCD_AUTHENT, data, 12, back_data, back_bits, backLen)
        if (Read_RC522(Status2Reg).toInt() and 0x08 == 0) status = MI_ERR
        return status
    }

    //
    fun Auth_Card(auth_mode: Byte, sector: Byte, block: Byte, key: ByteArray, uid: ByteArray): Int {
        return Auth_Card(auth_mode, Sector2BlockAddress(sector, block), key, uid)
    }

    //Ends operations with Crypto1 usage.
    fun Stop_Crypto() {
        ClearBitMask(Status2Reg, 0x08.toByte())
    }

    //Reads data from block. You should be authenticated before calling read.
    //Returns tuple of (result state, read data).
    //block_address
    //back_data-data to be read,16 bytes
    fun Read(block_address: Byte, back_data: ByteArray): Int {
        var status: Int
        val data = ByteArray(4)
        val back_bits = IntArray(1)
        val backLen = IntArray(1)

        data[0] = PICC_READ
        data[1] = block_address
        Calculate_CRC(data)
        status = Write_Card(PCD_TRANSCEIVE, data, data.size, back_data, back_bits, backLen)
        if (backLen[0] == 16) status = MI_OK
        return status
    }

    //
    fun Read(sector: Byte, block: Byte, back_data: ByteArray): Int {
        return Read(Sector2BlockAddress(sector, block), back_data)
    }

    //Writes data to block. You should be authenticated before calling write.
    //Returns error state.
    //data-16 bytes
    fun Write(block_address: Byte, data: ByteArray): Int {
        var status: Int
        val buff = ByteArray(4)
        val buff_write = ByteArray(data.size + 2)
        val back_data = ByteArray(this.MAX_LEN)
        val back_bits = IntArray(1)
        val backLen = IntArray(1)
        var i: Int

        buff[0] = PICC_WRITE
        buff[1] = block_address
        Calculate_CRC(buff)
        status = Write_Card(PCD_TRANSCEIVE, buff, buff.size, back_data, back_bits, backLen)
        //System.out.println("write_card  status="+status);
        //System.out.println("back_bits[0]="+back_bits[0]+",(back_data[0] & 0x0F)="+(back_data[0] & 0x0F));
        if (status != MI_OK || back_bits[0] != 4 || back_data[0].toInt() and 0x0F != 0x0A) status = MI_ERR
        if (status == MI_OK) {
            i = 0
            while (i < data.size) {
                buff_write[i] = data[i]
                i++
            }
            Calculate_CRC(buff_write)
            status = Write_Card(PCD_TRANSCEIVE, buff_write, buff_write.size, back_data, back_bits, backLen)
            //System.out.println("write_card data status="+status);
            //System.out.println("back_bits[0]="+back_bits[0]+",(back_data[0] & 0x0F)="+(back_data[0] & 0x0F));
            if (status != MI_OK || back_bits[0] != 4 || back_data[0].toInt() and 0x0F != 0x0A) status = MI_ERR
        }
        return status
    }

    //
    fun Write(sector: Byte, block: Byte, data: ByteArray): Int {
        return Write(Sector2BlockAddress(sector, block), data)
    }

    //导出1K字节,64个扇区
    fun DumpClassic1K(key: ByteArray, uid: ByteArray): ByteArray {
        var i: Int
        var status: Int
        val data = ByteArray(1024)
        val buff = ByteArray(16)

        i = 0
        while (i < 64) {
            status = Auth_Card(PICC_AUTHENT1A, i.toByte(), key, uid)
            if (status == MI_OK) {
                status = Read(i.toByte(), buff)
                if (status == MI_OK)
                    System.arraycopy(buff, 0, data, i * 64, 16)
            }
            i++
        }
        return data
    }

    //Convert sector  to blockaddress
    //sector-0~15
    //block-0~3
    //return blockaddress
    private fun Sector2BlockAddress(sector: Byte, block: Byte): Byte {
        return if (sector < 0 || sector > 15 || block < 0 || block > 3) (-1).toByte() else (sector * 4 + block).toByte()
    }

    //uid-5 bytes
    fun Select_MirareOne(uid: ByteArray): Int {
        val back_bits = IntArray(1)
        val tagid = ByteArray(5)
        var status: Int

        status = Request(RaspRC522.PICC_REQIDL, back_bits)
        if (status != MI_OK) return status
        status = AntiColl(tagid)
        if (status != MI_OK) return status
        Select_Tag(tagid)
        System.arraycopy(tagid, 0, uid, 0, 5)

        return status
    }

    companion object {

        //RC522命令字
        val PCD_IDLE: Byte = 0x00
        val PCD_AUTHENT: Byte = 0x0E
        val PCD_RECEIVE: Byte = 0x08
        val PCD_TRANSMIT: Byte = 0x04
        val PCD_TRANSCEIVE: Byte = 0x0C
        val PCD_RESETPHASE: Byte = 0x0F
        val PCD_CALCCRC: Byte = 0x03

        //PICC命令字
        val PICC_REQIDL: Byte = 0x26
        val PICC_REQALL: Byte = 0x52
        val PICC_ANTICOLL = 0x93.toByte()
        val PICC_SElECTTAG = 0x93.toByte()
        val PICC_AUTHENT1A: Byte = 0x60
        val PICC_AUTHENT1B: Byte = 0x61
        val PICC_READ: Byte = 0x30
        val PICC_WRITE = 0xA0.toByte()
        val PICC_DECREMENT = 0xC0.toByte()
        val PICC_INCREMENT = 0xC1.toByte()
        val PICC_RESTORE = 0xC2.toByte()
        val PICC_TRANSFER = 0xB0.toByte()
        val PICC_HALT: Byte = 0x50

        //返回状态
        val MI_OK = 0
        val MI_NOTAGERR = 1
        val MI_ERR = 2

        //RC522寄存器地址
        val Reserved00: Byte = 0x00
        val CommandReg: Byte = 0x01
        val CommIEnReg: Byte = 0x02
        val DivlEnReg: Byte = 0x03
        val CommIrqReg: Byte = 0x04
        val DivIrqReg: Byte = 0x05
        val ErrorReg: Byte = 0x06
        val Status1Reg: Byte = 0x07
        val Status2Reg: Byte = 0x08
        val FIFODataReg: Byte = 0x09
        val FIFOLevelReg: Byte = 0x0A
        val WaterLevelReg: Byte = 0x0B
        val ControlReg: Byte = 0x0C
        val BitFramingReg: Byte = 0x0D
        val CollReg: Byte = 0x0E
        val Reserved01: Byte = 0x0F

        val Reserved10: Byte = 0x10
        val ModeReg: Byte = 0x11
        val TxModeReg: Byte = 0x12
        val RxModeReg: Byte = 0x13
        val TxControlReg: Byte = 0x14
        val TxAutoReg: Byte = 0x15
        val TxSelReg: Byte = 0x16
        val RxSelReg: Byte = 0x17
        val RxThresholdReg: Byte = 0x18
        val DemodReg: Byte = 0x19
        val Reserved11: Byte = 0x1A
        val Reserved12: Byte = 0x1B
        val MifareReg: Byte = 0x1C
        val Reserved13: Byte = 0x1D
        val Reserved14: Byte = 0x1E
        val SerialspeedReg: Byte = 0x1F

        val Reserved20: Byte = 0x20
        val CRCResultRegM: Byte = 0x21
        val CRCResultRegL: Byte = 0x22
        val Reserved21: Byte = 0x23
        val ModWidthReg: Byte = 0x24
        val Reserved22: Byte = 0x25
        val RFCfgReg: Byte = 0x26
        val GsNReg: Byte = 0x27
        val CWGsPReg: Byte = 0x28
        val ModGsPReg: Byte = 0x29
        val TModeReg: Byte = 0x2A
        val TPrescalerReg: Byte = 0x2B
        val TReloadRegH: Byte = 0x2C
        val TReloadRegL: Byte = 0x2D
        val TCounterValueRegH: Byte = 0x2E
        val TCounterValueRegL: Byte = 0x2F

        val Reserved30: Byte = 0x30
        val TestSel1Reg: Byte = 0x31
        val TestSel2Reg: Byte = 0x32
        val TestPinEnReg: Byte = 0x33
        val TestPinValueReg: Byte = 0x34
        val TestBusReg: Byte = 0x35
        val AutoTestReg: Byte = 0x36
        val VersionReg: Byte = 0x37
        val AnalogTestReg: Byte = 0x38
        val TestDAC1Reg: Byte = 0x39
        val TestDAC2Reg: Byte = 0x3A
        val TestADCReg: Byte = 0x3B
        val Reserved31: Byte = 0x3C
        val Reserved32: Byte = 0x3D
        val Reserved33: Byte = 0x3E
        val Reserved34: Byte = 0x3F
    }
}

fun sh(cmd: String, vararg arg: String) {
    println("+ $cmd ${arg.joinToString(" ")}")
    val pb = ProcessBuilder(cmd, *arg)
    pb.inheritIO()
    val rc = pb.start().waitFor()
    if (rc != 0) {
        println("warning: process terminated with exit code $rc")
    }
}

fun handleCard(uid: String) {
    val tags = FileReader("/var/lib/mpd/music/tags.dat").useLines { l ->
        l.map { line -> line.split(' ', limit = 2) }
                .map { it[0] to it.getOrNull(1) }
                .toMap()
    }
    val action = tags[uid]
    if (action == null) {
        println("no action found for card $uid")
        return
    }
    when {
        action.startsWith("x volume ") -> {
            sh("mpc", "volume", action.removePrefix("x volume "))
        }
        action.startsWith("x ") -> {
            println("error: invalid command: $action")
        }
        action.startsWith("l ") -> {
            sh("mpc", "clear")
            sh("mpc", "load", action.removePrefix("l "))
            sh("mpc", "play")
        }
        action.startsWith("p ") -> {
            sh("mpc", "clear")
            sh("mpc", "add", action.removePrefix("p "))
            sh("mpc", "play")
        }
        else -> {
            sh("mpc", "clear")
            sh("mpc", "add", action)
            sh("mpc", "play")
        }
    }
}

fun main() {
    System.setProperty("pi4j.linking", "dynamic")
    val rrc522 = RaspRC522()
    val back_bits = IntArray(1)
    while (true) {
        if (rrc522.Request(RaspRC522.PICC_REQIDL, back_bits) == RaspRC522.MI_OK) {
            val back_data = ByteArray(5)
            if (rrc522.AntiColl(back_data) == RaspRC522.MI_OK) {
                handleCard(String.format("%d,%d,%d,%d,%d", back_data[0],
                        back_data[1], back_data[2], back_data[3], back_data[4]))
            }
        }
        Thread.sleep(250)
    }
}
