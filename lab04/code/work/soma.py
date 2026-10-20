import sys
numeros = [int(x) for x in sys.stdin.read().split()]
print(sum(numeros))