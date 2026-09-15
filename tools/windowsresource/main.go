// Copyright 2026 Jigsaw Operations LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package main

import (
	"bytes"
	"encoding/binary"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

const (
	imageFileMachineAMD64        = 0x8664
	imageScnCntInitializedData   = 0x00000040
	imageScnMemRead              = 0x40000000
	imageRelAMD64Addr32NB        = 0x0003
	imageSymClassStatic          = 3
	resourceDirectoryIsDirectory = 0x80000000

	rtIcon        = 3
	rtGroupIcon   = 14
	langEnglishUS = 1033
)

type iconEntry struct {
	width      byte
	height     byte
	colorCount byte
	reserved   byte
	planes     uint16
	bitCount   uint16
	bytesInRes uint32
	image      []byte
	id         uint16
}

type resourceBuilder struct {
	data   []byte
	relocs []uint32
}

func main() {
	iconPath := flag.String("ico", "", "input .ico path")
	outPath := flag.String("out", "", "output .syso path")
	flag.Parse()
	if *iconPath == "" || *outPath == "" {
		fmt.Fprintln(os.Stderr, "usage: go run ./tools/windowsresource -ico path\\intra.ico -out path\\resource.syso")
		os.Exit(2)
	}
	icons, err := loadICO(*iconPath)
	if err != nil {
		fatal(err)
	}
	rsrc := buildResourceSection(icons)
	if err := os.MkdirAll(filepath.Dir(*outPath), 0755); err != nil {
		fatal(err)
	}
	if err := os.WriteFile(*outPath, buildCOFF(rsrc), 0644); err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}

func loadICO(path string) ([]iconEntry, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(raw) < 6 {
		return nil, fmt.Errorf("ico file is too short")
	}
	if binary.LittleEndian.Uint16(raw[0:2]) != 0 || binary.LittleEndian.Uint16(raw[2:4]) != 1 {
		return nil, fmt.Errorf("unsupported ico header")
	}
	count := int(binary.LittleEndian.Uint16(raw[4:6]))
	if count == 0 {
		return nil, fmt.Errorf("ico contains no images")
	}
	if len(raw) < 6+count*16 {
		return nil, fmt.Errorf("ico directory is truncated")
	}
	icons := make([]iconEntry, 0, count)
	for i := 0; i < count; i++ {
		off := 6 + i*16
		size := binary.LittleEndian.Uint32(raw[off+8 : off+12])
		imageOff := binary.LittleEndian.Uint32(raw[off+12 : off+16])
		end := imageOff + size
		if end > uint32(len(raw)) || imageOff > end {
			return nil, fmt.Errorf("ico image %d points outside file", i)
		}
		image := make([]byte, size)
		copy(image, raw[imageOff:end])
		icons = append(icons, iconEntry{
			width:      raw[off],
			height:     raw[off+1],
			colorCount: raw[off+2],
			reserved:   raw[off+3],
			planes:     binary.LittleEndian.Uint16(raw[off+4 : off+6]),
			bitCount:   binary.LittleEndian.Uint16(raw[off+6 : off+8]),
			bytesInRes: size,
			image:      image,
			id:         uint16(i + 1),
		})
	}
	return icons, nil
}

func buildResourceSection(icons []iconEntry) resourceBuilder {
	b := resourceBuilder{}
	root := b.addDirectory(0, 2)
	iconDir := b.addDirectory(0, uint16(len(icons)))
	groupDir := b.addDirectory(0, 1)
	b.setDirectoryEntry(root, 0, rtIcon, uint32(iconDir), true)
	b.setDirectoryEntry(root, 1, rtGroupIcon, uint32(groupDir), true)

	for i, icon := range icons {
		langDir := b.addDirectory(0, 1)
		b.setDirectoryEntry(iconDir, i, uint32(icon.id), uint32(langDir), true)
		dataEntry := b.addDataEntry(icon.image)
		b.setDirectoryEntry(langDir, 0, langEnglishUS, uint32(dataEntry), false)
	}

	groupLangDir := b.addDirectory(0, 1)
	b.setDirectoryEntry(groupDir, 0, 1, uint32(groupLangDir), true)
	groupEntry := b.addDataEntry(buildGroupIconData(icons))
	b.setDirectoryEntry(groupLangDir, 0, langEnglishUS, uint32(groupEntry), false)
	return b
}

func buildGroupIconData(icons []iconEntry) []byte {
	var buf bytes.Buffer
	write16(&buf, 0)
	write16(&buf, 1)
	write16(&buf, uint16(len(icons)))
	for _, icon := range icons {
		buf.WriteByte(icon.width)
		buf.WriteByte(icon.height)
		buf.WriteByte(icon.colorCount)
		buf.WriteByte(icon.reserved)
		write16(&buf, icon.planes)
		write16(&buf, icon.bitCount)
		write32(&buf, icon.bytesInRes)
		write16(&buf, icon.id)
	}
	return buf.Bytes()
}

func (b *resourceBuilder) addDirectory(named, ids uint16) int {
	b.align(4)
	off := len(b.data)
	b.data = append(b.data, make([]byte, 16+int(named+ids)*8)...)
	binary.LittleEndian.PutUint16(b.data[off+12:off+14], named)
	binary.LittleEndian.PutUint16(b.data[off+14:off+16], ids)
	return off
}

func (b *resourceBuilder) setDirectoryEntry(dirOff, index int, id, target uint32, directory bool) {
	entryOff := dirOff + 16 + index*8
	binary.LittleEndian.PutUint32(b.data[entryOff:entryOff+4], id)
	if directory {
		target |= resourceDirectoryIsDirectory
	}
	binary.LittleEndian.PutUint32(b.data[entryOff+4:entryOff+8], target)
}

func (b *resourceBuilder) addDataEntry(payload []byte) int {
	b.align(4)
	entryOff := len(b.data)
	b.data = append(b.data, make([]byte, 16)...)
	b.align(4)
	dataOff := len(b.data)
	b.data = append(b.data, payload...)
	b.align(4)
	binary.LittleEndian.PutUint32(b.data[entryOff:entryOff+4], uint32(dataOff))
	binary.LittleEndian.PutUint32(b.data[entryOff+4:entryOff+8], uint32(len(payload)))
	b.relocs = append(b.relocs, uint32(entryOff))
	return entryOff
}

func (b *resourceBuilder) align(boundary int) {
	for len(b.data)%boundary != 0 {
		b.data = append(b.data, 0)
	}
}

func buildCOFF(r resourceBuilder) []byte {
	const fileHeaderSize = 20
	const sectionHeaderSize = 40
	const relocSize = 10
	const symbolSize = 18

	rawPtr := uint32(fileHeaderSize + sectionHeaderSize)
	relocPtr := rawPtr + uint32(len(r.data))
	symbolPtr := relocPtr + uint32(len(r.relocs)*relocSize)
	var out bytes.Buffer

	write16(&out, imageFileMachineAMD64)
	write16(&out, 1)
	write32(&out, 0)
	write32(&out, symbolPtr)
	write32(&out, 2)
	write16(&out, 0)
	write16(&out, 0)

	writeName(&out, ".rsrc")
	write32(&out, 0)
	write32(&out, 0)
	write32(&out, uint32(len(r.data)))
	write32(&out, rawPtr)
	write32(&out, relocPtr)
	write32(&out, 0)
	write16(&out, uint16(len(r.relocs)))
	write16(&out, 0)
	write32(&out, imageScnCntInitializedData|imageScnMemRead)

	out.Write(r.data)
	for _, va := range r.relocs {
		write32(&out, va)
		write32(&out, 0)
		write16(&out, imageRelAMD64Addr32NB)
	}

	writeName(&out, ".rsrc")
	write32(&out, 0)
	write16(&out, 1)
	write16(&out, 0)
	out.WriteByte(imageSymClassStatic)
	out.WriteByte(1)

	write32(&out, uint32(len(r.data)))
	write16(&out, uint16(len(r.relocs)))
	write16(&out, 0)
	write32(&out, 0)
	write16(&out, 0)
	out.WriteByte(0)
	out.Write(make([]byte, symbolSize-15))
	write32(&out, 4)
	return out.Bytes()
}

func writeName(buf *bytes.Buffer, name string) {
	var raw [8]byte
	copy(raw[:], name)
	buf.Write(raw[:])
}

func write16(buf *bytes.Buffer, value uint16) {
	_ = binary.Write(buf, binary.LittleEndian, value)
}

func write32(buf *bytes.Buffer, value uint32) {
	_ = binary.Write(buf, binary.LittleEndian, value)
}
