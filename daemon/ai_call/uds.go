package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"
)

const apcmMagic = 0x4D435041 // 'APCM' LE

// StreamHeader is the 16-byte APCM header from HAL.
type StreamHeader struct {
	Rate     uint32
	Channels uint16
	Bits     uint16
	Kind     uint16 // 0=mixed 1=DL 2=UL
}

func kindName(k uint16) string {
	switch k {
	case 0:
		return "mixed"
	case 1:
		return "DL"
	case 2:
		return "UL"
	default:
		return "unknown"
	}
}

func dialUDS(sock string, timeout time.Duration) (net.Conn, error) {
	return net.DialTimeout("unix", sock, timeout)
}

func readAPCMHeader(r io.Reader) (StreamHeader, error) {
	var hdr [16]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return StreamHeader{}, err
	}
	magic := binary.LittleEndian.Uint32(hdr[0:4])
	if magic != apcmMagic {
		return StreamHeader{}, fmt.Errorf("bad APCM magic 0x%x", magic)
	}
	return StreamHeader{
		Rate:     binary.LittleEndian.Uint32(hdr[4:8]),
		Channels: binary.LittleEndian.Uint16(hdr[8:10]),
		Bits:     binary.LittleEndian.Uint16(hdr[10:12]),
		Kind:     binary.LittleEndian.Uint16(hdr[12:14]),
	}, nil
}
