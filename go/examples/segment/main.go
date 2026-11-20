// Transcribe one short, headerless PCM file and print its word timestamps.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"time"

	"github.com/MyVocal-AI/enterprise-echo-v2-realtime-sdks/go"
)

func main() {
	path := flag.String("pcm", "../samples/english.pcm", "path to raw PCM S16LE, 16 kHz, mono")
	timeout := flag.Duration("timeout", 30*time.Second, "limit for connection, audio and results")
	timestamps := flag.Bool("timestamps", true, "request word timings")
	flag.Parse()
	log.SetFlags(0)
	if err := run(*path, *timeout, *timestamps); err != nil {
		log.Fatal(err)
	}
}

func run(path string, timeout time.Duration, timestamps bool) error {
	pcm, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	interrupt, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	ctx, cancel := context.WithTimeout(interrupt, timeout)
	defer cancel()

	client := asr.NewClient(os.Getenv("MYVOCAL_API_KEY"))
	defer client.Close()
	result, err := client.TranscribeSegment(ctx, pcm, asr.Options{WordTimestamps: timestamps})
	if result.Committed != nil {
		fmt.Println(result.Text)
	}
	for _, word := range result.Words {
		fmt.Printf("%q\t%s\t%s\n", word.Text, seconds(word.Start), seconds(word.End))
	}
	return err
}

func seconds(value *float64) string {
	if value == nil {
		return "—"
	}
	return fmt.Sprintf("%.2f s", *value)
}
